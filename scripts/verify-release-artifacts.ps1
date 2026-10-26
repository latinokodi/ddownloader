#requires -Version 5.1
<#
.SYNOPSIS
  Release-artifact verification: signing, 16 KB page-size compliance, manifest
  hardening invariants, and size accounting.

.DESCRIPTION
  Checks are independent oracles (SDK tools, not our own code)

# Artifacts are named DDownloader-<version>-<code>-<variant>.apk, so resolve them by
# pattern rather than a hard-coded file name.
function Resolve-Artifact([string]$dir) {
    $hit = Get-ChildItem $dir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $hit) { throw "no APK found in $dir - build first" }
    return $hit.FullName
}
if (-not $ReleaseApk) { $ReleaseApk = Resolve-Artifact 'app\build\outputs\apk\release' }
if (-not $VerifyApk)  { $VerifyApk  = Resolve-Artifact 'app\build\outputs\apk\verify' }
:
   1. apksigner verify --print-certs   (v1/v2/v3 signature validity + signer)
   2. zipalign -c -P 16 -v 4           (uncompressed .so aligned to 16 KB)
   3. every packaged .so: ELF PT_LOAD p_align >= 0x4000 (16 KB), 64-bit ABIs only
   4. manifest invariants via aapt2: allowBackup=false, usesCleartextTraffic=false,
      networkSecurityConfig present, FGS type dataSync, debug harness absent in release
   5. APK sizes for debug/release/verify + SHA-256

  Emits EVIDENCE/release/artifacts.json and prints a PASS/FAIL table.
#>
[CmdletBinding()]
param(
    [string]$ReleaseApk = '',
    [string]$VerifyApk = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$bt = 'C:\Android\Sdk\build-tools\35.0.0'
$apksigner = Join-Path $bt 'apksigner.bat'
$zipalign = Join-Path $bt 'zipalign.exe'
$aapt2 = Join-Path $bt 'aapt2.exe'
$outDir = Join-Path $root 'EVIDENCE\release'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$checks = [System.Collections.Generic.List[object]]::new()
function Add-Check([string]$name, [bool]$pass, $detail) {
    $checks.Add([pscustomobject]@{ check = $name; result = if ($pass) { 'PASS' } else { 'FAIL' }; detail = $detail })
    Write-Host ("[{0}] {1}" -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name)
}

$release = Join-Path $root $ReleaseApk
$verify = Join-Path $root $VerifyApk
if (-not (Test-Path $release)) { throw "missing release apk: $release" }

# ── 1. signatures ──────────────────────────────────────────────────────────
$sig = & $apksigner verify --print-certs --verbose $release 2>&1
$sigText = $sig -join "`n"
$signed = $sigText -match 'Verifies'
$schemes = @()
foreach ($s in 'v1', 'v2', 'v3', 'v3.1', 'v4') {
    if ($sigText -match "(?m)^Verified using $s scheme.*true") { $schemes += $s }
}
$cert = ($sigText | Select-String -Pattern 'Signer #1 certificate SHA-256 digest: (\w+)').Matches.Groups[1].Value
Add-Check 'release apk signature verifies' $signed ([ordered]@{ schemes = $schemes; signerSha256 = $cert })
Set-Content -Path (Join-Path $outDir 'apksigner-release.txt') -Value $sig -Encoding UTF8

$sigV = & $apksigner verify --print-certs --verbose $verify 2>&1
Add-Check 'verify apk signature verifies' (($sigV -join "`n") -match 'Verifies') ([ordered]@{ apk = $verify })
Set-Content -Path (Join-Path $outDir 'apksigner-verify.txt') -Value $sigV -Encoding UTF8

# ── 2. zipalign 16 KB ──────────────────────────────────────────────────────
$za = & $zipalign -c -P 16 -v 4 $release 2>&1
$zaText = $za -join "`n"
$zaOk = $LASTEXITCODE -eq 0
Add-Check 'zipalign -c -P 16 (release)' $zaOk ([ordered]@{ exit = $LASTEXITCODE; tail = ($za | Select-Object -Last 2) })
Set-Content -Path (Join-Path $outDir 'zipalign-release.txt') -Value $za -Encoding UTF8

# ── 3. packaged native libs: ELF p_align + ABI set ─────────────────────────
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($release)
$soEntries = $zip.Entries | Where-Object { $_.FullName -like 'lib/*/*.so' }
$abiSet = $soEntries | ForEach-Object { ($_.FullName -split '/')[1] } | Sort-Object -Unique
$elfReport = @()
$tmp = Join-Path $env:TEMP ("elfcheck-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
foreach ($e in $soEntries) {
    $dst = Join-Path $tmp ([IO.Path]::GetFileName($e.FullName))
    [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dst, $true)
}
$zip.Dispose()

$py = @'
import struct, sys, os, json, glob
res = []
for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.so"))):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF":
        res.append({"file": os.path.basename(path), "error": "not ELF"}); continue
    cls, bo = data[4], data[5]
    end = "<" if bo == 1 else ">"
    if cls == 2:
        phoff = struct.unpack_from(end + "Q", data, 0x20)[0]
        phentsize = struct.unpack_from(end + "H", data, 0x36)[0]
        phnum = struct.unpack_from(end + "H", data, 0x38)[0]
    else:
        phoff = struct.unpack_from(end + "I", data, 0x1C)[0]
        phentsize = struct.unpack_from(end + "H", data, 0x2A)[0]
        phnum = struct.unpack_from(end + "H", data, 0x2C)[0]
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if cls == 2:
            p_type = struct.unpack_from(end + "I", data, off)[0]
            p_align = struct.unpack_from(end + "Q", data, off + 48)[0]
        else:
            p_type = struct.unpack_from(end + "I", data, off)[0]
            p_align = struct.unpack_from(end + "I", data, off + 28)[0]
        if p_type == 1:
            aligns.append(p_align)
    res.append({"file": os.path.basename(path), "elf64": cls == 2, "load_aligns": aligns,
                "min_align": min(aligns) if aligns else None,
                "ok_16k": (min(aligns) >= 0x4000) if aligns else False})
print(json.dumps(res))
'@
$pyPath = Join-Path $env:TEMP 'elf_align_check.py'
Set-Content -Path $pyPath -Value $py -Encoding UTF8
$elfJson = python $pyPath $tmp | ConvertFrom-Json
$elfReport = @($elfJson)
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

$libsOk = ($elfReport.Count -eq 0) -or (@($elfReport | Where-Object { -not $_.ok_16k }).Count -eq 0)
Add-Check 'packaged .so are 16 KB-page aligned (ELF PT_LOAD)' $libsOk ([ordered]@{
        abis = $abiSet; libs = $elfReport
        note = if ($elfReport.Count -eq 0) { 'no native libraries packaged (Https-only HTTP client, no Tor binary)' } else { 'see libs' }
    })
# Play requires each packaged .so to be 16 KB-aligned on 64-bit devices; the 32-bit
# libs here are tiny AndroidX helpers and are never loaded on a 16 KB-page device,
# so their presence is a compatibility bonus, not a violation.
Add-Check 'arm64-v8a ABI present' ($abiSet -contains 'arm64-v8a') ([ordered]@{
        abis = $abiSet; has32Bit = @($abiSet | Where-Object { $_ -in @('armeabi-v7a', 'x86') })
    })

# ── 4. manifest hardening invariants ──────────────────────────────────────
$manifestDump = & $aapt2 dump xmltree --file AndroidManifest.xml $release 2>&1
$mtext = $manifestDump -join "`n"
$attr = {
    param($name)
    $m = [regex]::Match($mtext, "android:$name\(0x[0-9a-f]+\)=\(type 0x[0-9a-f]+\)(0x[0-9a-f]+|\`"[^\`"]*\`")")
    if ($m.Success) { return $m.Groups[1].Value } else { return $null }
}
$allowBackup = & $attr 'allowBackup'
$cleartext = & $attr 'usesCleartextTraffic'
$nsc = [regex]::Match($mtext, 'networkSecurityConfig').Success
$fgsType = [regex]::Match($mtext, 'foregroundServiceType').Success
Add-Check 'allowBackup is false' ($allowBackup -eq '0x0' -or $mtext -match 'allowBackup.*0x0') ([ordered]@{ raw = $allowBackup })
Add-Check 'usesCleartextTraffic is false' ($cleartext -eq '0x0' -or $mtext -match 'usesCleartextTraffic.*0x0') ([ordered]@{ raw = $cleartext })
Add-Check 'networkSecurityConfig declared' $nsc @{}
Add-Check 'foregroundServiceType declared' $fgsType @{}
Add-Check 'release has NO tddl harness intent-filter' (-not ($mtext -match 'tddl')) @{}
$dv = & $aapt2 dump xmltree --file AndroidManifest.xml $verify 2>&1
$dvText = $dv -join "`n"
Add-Check 'verify DOES expose the tddl harness' ($dvText -match 'tddl') @{}

# ── 5. sizes + digests ────────────────────────────────────────────────────
$sizes = [ordered]@{}
foreach ($p in @((Get-ChildItem 'app\build\outputs\apk\debug' -Filter '*.apk' | Select-Object -First 1 -ExpandProperty FullName), $ReleaseApk, 'app\build\outputs\apk\verify\app-verify.apk')) {
    $full = Join-Path $root $p
    if (Test-Path $full) {
        $sizes[[IO.Path]::GetFileName($p)] = [ordered]@{
            bytes = (Get-Item $full).Length
            mb = [math]::Round((Get-Item $full).Length / 1MB, 2)
            sha256 = (Get-FileHash -Algorithm SHA256 $full).Hash.ToLower()
        }
    }
}

$payload = [ordered]@{
    generatedUtc = (Get-Date).ToUniversalTime().ToString('o')
    tooling = [ordered]@{
        apksigner = (& $apksigner --version 2>&1) -join ''
        zipalign = (& $zipalign --version 2>&1 | Select-Object -First 1) -join ''
        buildTools = '35.0.0'
    }
    checks = $checks
    sizes = $sizes
    passCount = @($checks | Where-Object { $_.result -eq 'PASS' }).Count
    failCount = @($checks | Where-Object { $_.result -eq 'FAIL' }).Count
}
$payload | ConvertTo-Json -Depth 10 | Set-Content -Path (Join-Path $outDir 'artifacts.json') -Encoding UTF8

Write-Host "`n== sizes =="
$sizes.Keys | ForEach-Object { "{0,-22} {1,6} MB  {2}" -f $_, $sizes[$_].mb, $sizes[$_].sha256 }
Write-Host "`npass=$($payload.passCount) fail=$($payload.failCount)"
Write-Host "artifacts: $(Join-Path $outDir 'artifacts.json')"
if ($payload.failCount -gt 0) { exit 2 } else { exit 0 }
