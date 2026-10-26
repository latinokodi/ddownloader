# env-boot-wait.ps1 -- wait for emulator boot and record deterministic facts.
# Owner: env-runner. ASCII only.
param(
  [string]$Serial = 'emulator-5554',
  [int]$TimeoutSeconds = 480,
  [string]$OutJson = 'F:\PyApps\ddandroid\EVIDENCE\env\boot-timing.json'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'

function Now { (Get-Date).ToUniversalTime().ToString('o') }
$t0 = Get-Date
$result = [ordered]@{
  serial = $Serial
  started_utc = (Now)
  device_seen_utc = $null
  device_seen_after_seconds = $null
  boot_completed = $null
  boot_completed_utc = $null
  boot_completed_after_seconds = $null
  timed_out = $false
  error = $null
  poll_log = @()
}

& $adb start-server 2>&1 | Out-Null

# Phase 1: wait for device to appear
$deadline = $t0.AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
  $devs = & $adb devices 2>&1 | Out-String
  if ($devs -match [regex]::Escape($Serial) + '\s+device') {
    $result.device_seen_utc = Now
    $result.device_seen_after_seconds = [math]::Round(((Get-Date) - $t0).TotalSeconds, 2)
    break
  }
  if ($devs -match [regex]::Escape($Serial) + '\s+offline') {
    $result.poll_log += "[$((Now))] device offline"
  }
  Start-Sleep -Milliseconds 1000
}

if ($null -eq $result.device_seen_utc) {
  $result.timed_out = $true
  $result.error = 'device never appeared in adb devices'
  $result | ConvertTo-Json -Depth 6 | Out-File $OutJson -Encoding utf8
  Write-Output "[X] TIMEOUT waiting for $Serial"
  exit 1
}
Write-Output "[OK] device seen after $($result.device_seen_after_seconds)s"

# Phase 2: poll sys.boot_completed
$boot = ''
while ((Get-Date) -lt $deadline) {
  $boot = (& $adb -s $Serial shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
  if ($boot -eq '1') { break }
  Start-Sleep -Milliseconds 2000
}
if ($boot -eq '1') {
  $result.boot_completed = 1
  $result.boot_completed_utc = Now
  $result.boot_completed_after_seconds = [math]::Round(((Get-Date) - $t0).TotalSeconds, 2)
  Write-Output "[OK] sys.boot_completed=1 after $($result.boot_completed_after_seconds)s"
} else {
  $result.timed_out = $true
  $result.error = "sys.boot_completed never became 1 (last='$boot')"
  Write-Output "[X] TIMEOUT sys.boot_completed last='$boot'"
}

$result | ConvertTo-Json -Depth 6 | Out-File $OutJson -Encoding utf8
Write-Output "written $OutJson"
exit 0
