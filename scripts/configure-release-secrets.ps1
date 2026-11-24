# configure-release-secrets.ps1 -- one-time setup for .github/workflows/release.yml
#
# Uploads the four signing secrets the release workflow needs, reading them from the
# same `keystore.properties` + `keystore/release.jks` you already use for local
# releases. Values are passed to `gh` on stdin, so they never appear in the process
# list or in this session's output.
#
#   pwsh -NoProfile -File scripts\configure-release-secrets.ps1
#
# Requires: gh authenticated with repo admin (it already is) and the keystore present.

[CmdletBinding()]
param(
    [string]$Repo,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$propsPath = Join-Path $root 'keystore.properties'

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $propsPath)) {
    Fail "keystore.properties not found at $propsPath (it is git-ignored; restore it from the off-repo backup)"
}

# Parse the same keys app/build.gradle.kts reads.
$props = @{}
Get-Content $propsPath | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $props[$matches[1]] = $matches[2].Trim() }
}
foreach ($k in 'storeFile', 'storePassword', 'keyAlias', 'keyPassword') {
    if (-not $props[$k]) { Fail "keystore.properties is missing '$k'" }
}

$jks = Join-Path $root $props['storeFile']
if (-not (Test-Path $jks)) { Fail "keystore file not found: $jks" }

if (-not $Repo) {
    $origin = git -C $root remote get-url origin
    if ($origin -match 'github\.com[:/](?<o>[^/]+)/(?<r>[^/.]+)') {
        $Repo = "$($matches['o'])/$($matches['r'])"
    } else {
        Fail "could not infer the repo from 'origin'; pass -Repo owner/name"
    }
}

# The expected signer digest is what release.yml checks the APK against. Reading it
# from the keystore (rather than trusting a copy-pasted value) is what makes the
# signature gate meaningful.
$keytool = $null
foreach ($candidate in @(
    "$env:JAVA_HOME\bin\keytool.exe",
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
    "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot\bin\keytool.exe"
)) {
    if ($candidate -and (Test-Path $candidate)) { $keytool = $candidate; break }
}
if (-not $keytool) {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source }
}
if (-not $keytool) { Fail "keytool not found; set JAVA_HOME or install a JDK 21" }

$listing = & $keytool -list -v -keystore $jks -storepass $props['storePassword'] -alias $props['keyAlias'] 2>&1
if ($LASTEXITCODE -ne 0) {
    $listing | ForEach-Object { Write-Host $_ }
    Fail "keytool could not read the keystore (wrong storePassword or alias?)"
}
$sha = ($listing | Select-String -Pattern 'SHA256:' | Select-Object -First 1).ToString() -replace '.*SHA256:\s*', ''
$signer = ($sha -replace '[:\s]', '').ToLowerInvariant()
if ($signer.Length -ne 64) { Fail "could not parse the signer digest from keytool output" }

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))

$secrets = [ordered]@{
    KEYSTORE_B64              = $b64
    KEYSTORE_STORE_PASSWORD   = $props['storePassword']
    KEYSTORE_KEY_ALIAS        = $props['keyAlias']
    KEYSTORE_KEY_PASSWORD     = $props['keyPassword']
    RELEASE_SIGNER_SHA256     = $signer
}

Write-Host "repo:   $Repo"
Write-Host "jks:    $jks ($([math]::Round((Get-Item $jks).Length/1KB,1)) KB)"
Write-Host "alias:  $($props['keyAlias'])"
Write-Host "signer: $signer"
Write-Host ""

if ($DryRun) {
    foreach ($k in $secrets.Keys) {
        $len = $secrets[$k].Length
        Write-Host ("  {0,-26} {1} chars (not uploaded: -DryRun)" -f $k, $len)
    }
    exit 0
}

foreach ($k in $secrets.Keys) {
    # Value travels on stdin so it is not exposed via the process command line.
    $secrets[$k] | gh secret set $k --repo $Repo
    if ($LASTEXITCODE -ne 0) { Fail "failed to set $k" }
    Write-Host "  set $k" -ForegroundColor Green
}

Write-Host ""
$listed = gh secret list --repo $Repo
Write-Host $listed
Write-Host ""
Write-Host "Done. The next push to main builds and publishes a release." -ForegroundColor Green
Write-Host "Remember: bump appVersionName/appVersionCode in app/build.gradle.kts," -ForegroundColor Yellow
Write-Host "otherwise the workflow refuses to publish (it would never reach users)." -ForegroundColor Yellow
