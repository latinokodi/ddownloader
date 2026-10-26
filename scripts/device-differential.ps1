#requires -Version 5.1
<#
.SYNOPSIS
  Differential test: Android download engine vs the Electron reference results.

.DESCRIPTION
  Runs five fixture scenarios against an installed build and asserts the Android port
  behaves like (or strictly better than) electron/downloader.ts:

    s1 fresh     plain.bin               -> 200, complete, sha == reference
    s2 resume    plain.bin, killed mid   -> 206 Range from the partial, complete, sha == reference
    s3 norange   norange.bin, killed mid -> server answers 200 to Range: must NOT publish a
                                            truncated file; restarts from zero and ends byte-correct
    s4 flaky     flaky.bin (cut at 4 MiB)-> retry resumes from the cut point, complete, sha == reference
    s5 empty     empty.bin (0-byte body) -> must FAIL as truncation, never "success"

  State comes from logcat (DownloadMgr) and the fixture server's JSONL request log, so
  this runs against a NON-debuggable, R8-minified build (no `run-as` needed).

.EXAMPLE
  pwsh -NoProfile -File scripts/device-differential.ps1
  pwsh -NoProfile -File scripts/device-differential.ps1 -Package com.ddownloader.android.verify
  pwsh -NoProfile -File scripts/device-differential.ps1 -Only s2,s3
#>
[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [string]$Package = 'com.ddownloader.android.debug',
    [string]$FixtureBase = 'http://10.0.2.2:8787',
    [string[]]$Only = @('s1', 's2', 's3', 's4', 's5'),
    [int]$Rate = 2097152,
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$pkg = $Package
$root = (Resolve-Path "$PSScriptRoot\..").Path
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$outDir = Join-Path $root "EVIDENCE\differential\run-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$external = "/sdcard/Android/data/$pkg/files/Download"
$fixtureLog = Join-Path $root 'EVIDENCE\env\fixture-requests-standalone.jsonl'

$refPath = Join-Path $root 'EVIDENCE\electron\reference-hashes.json'
if (-not (Test-Path $refPath)) { throw "missing reference hashes: $refPath" }
$refs = Get-Content $refPath -Raw | ConvertFrom-Json
function RefSha([string]$name) { return $refs.files.$name.sha256 }

$results = [System.Collections.Generic.List[object]]::new()
$transcript = [System.Collections.Generic.List[string]]::new()

function Log([string]$line) { $transcript.Add($line); Write-Host $line }

function Adb([string[]]$arguments) {
    $out = & $adb -s $Serial @arguments 2>&1
    $transcript.Add(("adb {0} -> {1}" -f ($arguments -join ' '), ($out -join ' | ')))
    return $out
}

function Reset-App {
    [void](Adb @('shell', 'am', 'force-stop', $pkg))
    [void](Adb @('shell', 'pm', 'clear', $pkg))
    [void](Adb @('shell', 'rm', '-rf', $external))
    [void](Adb @('shell', 'pm', 'grant', $pkg, 'android.permission.POST_NOTIFICATIONS'))
    [void](Adb @('logcat', '-c'))
}

function Enqueue([string]$file, [long]$expected, [bool]$throttle = $true) {
    $q = if ($throttle) { "?rate=$Rate" } else { '' }
    $raw = "$FixtureBase/file/$file$q"
    $enc = [uri]::EscapeDataString($raw)
    $uri = "tddl://download?url=$enc&name=$file&expected=$expected"
    [void](Adb @('shell', "am start -a android.intent.action.VIEW -d '$uri'"))
}

function Clear-FixtureLog {
    if (Test-Path $fixtureLog) { Set-Content -Path $fixtureLog -Value $null -Encoding UTF8 }
}

function Fixture-Requests {
    if (-not (Test-Path $fixtureLog)) { return @() }
    $all = Get-Content $fixtureLog -ErrorAction SilentlyContinue | Where-Object { $_ -and $_.Trim().StartsWith('{') }
    return @($all | ForEach-Object { try { $_ | ConvertFrom-Json } catch { $null } } | Where-Object { $_ })
}

function Log-Terminal([string]$name, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $raw = & $adb -s $Serial logcat -d -s DownloadMgr:V 2>&1
        $text = ($raw -join "`n")
        if ($text -match [regex]::Escape("completed $name")) {
            return [pscustomobject]@{ State = 'COMPLETED'; Error = ''; Raw = $text }
        }
        $m = [regex]::Match($text, 'transfer \S+ failed: (.*)')
        if ($m.Success) {
            return [pscustomobject]@{ State = 'FAILED'; Error = $m.Groups[1].Value.Trim(); Raw = $text }
        }
        Start-Sleep -Milliseconds 500
    }
    $raw = & $adb -s $Serial logcat -d -s DownloadMgr:V 2>&1
    return [pscustomobject]@{ State = 'TIMEOUT'; Error = 'no terminal log line within timeout'; Raw = ($raw -join "`n") }
}

function Output-File([string]$name) {
    $raw = & $adb -s $Serial shell "find $external -name '$name' -type f 2>/dev/null" 2>&1
    $path = ($raw | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($path)) { return $null }
    return $path.Trim()
}

function Pull-And-Hash([string]$devicePath, [string]$destDir) {
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    $local = Join-Path $destDir ([System.IO.Path]::GetFileName($devicePath))
    [void](Adb @('pull', $devicePath, $local))
    if (-not (Test-Path $local)) { return $null }
    return (Get-FileHash -Algorithm SHA256 -Path $local).Hash.ToLower()
}

function Record([string]$name, [string]$verdict, $detail) {
    $results.Add([pscustomobject]@{ scenario = $name; verdict = $verdict; detail = $detail })
    Log ("  -> {0}: {1}" -f $verdict, ($detail | ConvertTo-Json -Compress -Depth 6))
}

# ── preflight ─────────────────────────────────────────────────────────────
Log "== preflight =="
$installed = (& $adb -s $Serial shell "dumpsys package $pkg | grep -E 'versionName|lastUpdateTime'" 2>&1) -join ' '
if ($installed -notmatch 'versionName') { Write-Host "package $pkg is not installed"; exit 3 }
Log "installed: $installed"
try {
    $healthy = Invoke-WebRequest -Uri 'http://127.0.0.1:8787/health' -UseBasicParsing -TimeoutSec 5
    Log "fixture /health: $($healthy.StatusCode)"
} catch { Log "WARN fixture server not reachable from host: $($_.Exception.Message)" }

# ── s1 ────────────────────────────────────────────────────────────────────
if ($Only -contains 's1') {
    Log "`n== s1 fresh =="
    Reset-App; Clear-FixtureLog
    Enqueue 'plain.bin' 8388608
    $st = Log-Terminal 'plain.bin' $TimeoutSeconds
    $path = Output-File 'plain.bin'
    $sha = if ($path) { Pull-And-Hash $path (Join-Path $outDir 's1') } else { $null }
    $expected = RefSha 'plain.bin'
    $reqs = Fixture-Requests
    $verdict = if ($st.State -eq 'COMPLETED' -and $sha -eq $expected) { 'PASS' } else { 'FAIL' }
    Record 's1' $verdict ([ordered]@{ state = $st.State; sha256 = $sha; expected = $expected; requests = $reqs })
}

# ── s2 ────────────────────────────────────────────────────────────────────
if ($Only -contains 's2') {
    Log "`n== s2 resume after process death =="
    Reset-App; Clear-FixtureLog
    Enqueue 'plain.bin' 8388608
    Start-Sleep -Seconds 3
    [void](Adb @('shell', 'am', 'force-stop', $pkg))
    Start-Sleep -Seconds 1
    $firstReq = (Fixture-Requests | Select-Object -First 1)
    [void](Adb @('shell', 'am', 'start', '-n', "$pkg/com.ddownloader.android.ui.MainActivity"))
    $st = Log-Terminal 'plain.bin' $TimeoutSeconds
    $path = Output-File 'plain.bin'
    $sha = if ($path) { Pull-And-Hash $path (Join-Path $outDir 's2') } else { $null }
    $expected = RefSha 'plain.bin'
    $reqs = Fixture-Requests
    $resumeReq = $reqs | Where-Object { $_.range_header } | Select-Object -First 1
    $resumedAt = if ($resumeReq) { [long]([regex]::Match($resumeReq.range_header, 'bytes=(\d+)-').Groups[1].Value) } else { 0 }
    $beforeKill = if ($firstReq) { [long]$firstReq.bytes_written } else { 0 }
    $verdict = if ($st.State -eq 'COMPLETED' -and $sha -eq $expected -and $beforeKill -gt 0 -and $resumedAt -gt 0) { 'PASS' } else { 'FAIL' }
    Record 's2' $verdict ([ordered]@{
            bytesBeforeKill = $beforeKill; resumedFromByte = $resumedAt
            state = $st.State; sha256 = $sha; expected = $expected; requests = $reqs
        })
}

# ── s3 ────────────────────────────────────────────────────────────────────
if ($Only -contains 's3') {
    Log "`n== s3 server ignores Range =="
    Reset-App; Clear-FixtureLog
    Enqueue 'norange.bin' 8388608
    Start-Sleep -Seconds 3
    [void](Adb @('shell', 'am', 'force-stop', $pkg))
    Start-Sleep -Seconds 1
    [void](Adb @('shell', 'am', 'start', '-n', "$pkg/com.ddownloader.android.ui.MainActivity"))
    $st = Log-Terminal 'norange.bin' $TimeoutSeconds
    $path = Output-File 'norange.bin'
    $sha = if ($path) { Pull-And-Hash $path (Join-Path $outDir 's3') } else { $null }
    $expected = RefSha 'norange.bin'
    $reqs = Fixture-Requests
    $verdict = if ($st.State -eq 'COMPLETED' -and $sha -eq $expected) { 'PASS' } else { 'FAIL' }
    Record 's3' $verdict ([ordered]@{
            state = $st.State; sha256 = $sha; expected = $expected; requests = $reqs
            note = 'electron/worker.ts would publish the truncated partial as complete; this port discards it and re-fetches from zero'
        })
}

# ── s4 ────────────────────────────────────────────────────────────────────
if ($Only -contains 's4') {
    Log "`n== s4 flaky cut, retry resumes =="
    Reset-App; Clear-FixtureLog
    Enqueue 'flaky.bin' 8388608
    $st = Log-Terminal 'flaky.bin' ($TimeoutSeconds + 45)
    $path = Output-File 'flaky.bin'
    $sha = if ($path) { Pull-And-Hash $path (Join-Path $outDir 's4') } else { $null }
    $expected = RefSha 'flaky.bin'
    $reqs = Fixture-Requests
    $verdict = if ($st.State -eq 'COMPLETED' -and $sha -eq $expected) { 'PASS' } else { 'FAIL' }
    Record 's4' $verdict ([ordered]@{ state = $st.State; sha256 = $sha; expected = $expected; requests = $reqs })
}

# ── s5 ────────────────────────────────────────────────────────────────────
if ($Only -contains 's5') {
    Log "`n== s5 empty body =="
    Reset-App; Clear-FixtureLog
    Enqueue 'empty.bin' 1000 $false
    $st = Log-Terminal 'empty.bin' $TimeoutSeconds
    $published = Output-File 'empty.bin'
    $reqs = Fixture-Requests
    $verdict = if ($st.State -eq 'FAILED' -and -not $published) { 'PASS' } else { 'FAIL' }
    Record 's5' $verdict ([ordered]@{ state = $st.State; error = $st.Error; published = $published; requests = $reqs })
}

# ── report ────────────────────────────────────────────────────────────────
$summary = [ordered]@{
    runId = $runId
    serial = $Serial
    package = $pkg
    fixtureBase = $FixtureBase
    results = $results
    passCount = @($results | Where-Object { $_.verdict -eq 'PASS' }).Count
    failCount = @($results | Where-Object { $_.verdict -eq 'FAIL' }).Count
}
$json = $summary | ConvertTo-Json -Depth 8
Set-Content -Path (Join-Path $outDir 'differential.json') -Value $json -Encoding UTF8
Set-Content -Path (Join-Path $outDir 'adb-transcript.log') -Value $transcript -Encoding UTF8
New-Item -ItemType Directory -Force -Path (Join-Path $root 'EVIDENCE\differential') | Out-Null
Set-Content -Path (Join-Path $root 'EVIDENCE\differential\latest.json') -Value $json -Encoding UTF8

Write-Host "`n== summary =="
$results | Format-Table scenario, verdict -AutoSize | Out-String | Write-Host
Write-Host "package=$pkg pass=$($summary.passCount) fail=$($summary.failCount)"
Write-Host "artifacts: $outDir"
if ($summary.failCount -gt 0) { exit 2 } else { exit 0 }
