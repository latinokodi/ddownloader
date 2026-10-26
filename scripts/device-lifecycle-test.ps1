# device-lifecycle-test.ps1 -- automated lifecycle/robustness test harness for the
# TorBox Android client (debug build). Owner: env-runner.
#
# It drives the app ONLY through its debug deep link and the Android CLI; it never
# modifies app code. Every adb command, every sample, every assertion and every
# pulled artifact is recorded under EVIDENCE/lifecycle/run-<timestamp>/ and also
# summarised in a machine-readable JSON + a PASS/FAIL table.
#
# Scenarios
#   a  process death mid-download   (force-stop, then relaunch -> must resume)
#   b  Doze                         (deviceidle force-idle -> must still finish)
#   c  network toggle               (wifi/data off -> on -> must finish)
#   d  queued jobs survive FGS stop (stopservice DownloadService -> both finish)
#
# Idempotent: each scenario force-stops the app, `pm clear`s it and removes its
# external files dir before running, so the harness can be re-run at any time.
#
# Exit codes: 0 = all requested scenarios PASS, 2 = at least one FAIL,
#             3 = preflight blocked (e.g. app not installed), 1 = harness error.
#
# ASCII only. PowerShell 7+.

param(
  [string]$Serial       = 'emulator-5554',
  [string]$Package      = 'com.ddownloader.android.debug',
  [string]$Service      = 'com.ddownloader.android.download.DownloadService',
  [string]$OutRoot      = 'F:\PyApps\ddandroid\EVIDENCE\lifecycle',
  [string]$FixtureLog   = 'F:\PyApps\ddandroid\EVIDENCE\env\fixture-requests-standalone.jsonl',
  [string]$FixtureHost  = '10.0.2.2',
  [int]$FixturePort     = 8787,
  [string]$ApkPath      = '',       # optional: installed/build APK for the manifest preflight check
  [string]$Aapt2        = 'C:\Android\Sdk\build-tools\35.0.0\aapt2.exe',
  [string]$Activity     = 'com.ddownloader.android.ui.MainActivity',
  [string]$ToolsDir     = 'F:\PyApps\ddandroid\EVIDENCE\captures\tools',
  [string[]]$Only       = @(),      # e.g. -Only a,c ; empty = all
  [int]$CompleteTimeoutSeconds = 120,
  [switch]$PreflightOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$Adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$RefHashesPath = 'F:\PyApps\ddandroid\EVIDENCE\electron\reference-hashes.json'
$StartTime = Get-Date
$RunStamp = $StartTime.ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$RunDir = Join-Path $OutRoot ("run-" + $RunStamp)
$TranscriptPath = Join-Path $RunDir 'adb-transcript.log'
$script:Transcript = New-Object System.Collections.Generic.List[string]
$script:ScenarioRecords = New-Object System.Collections.Generic.List[object]
# How partial files inside the app's private data dir are sampled:
#   'run-as'  -> debug/verify build is debuggable (preferred)
#   'root'    -> adb root fallback when run-as is refused
$script:PartialAccess = 'run-as'

New-Item -ItemType Directory -Force -Path $RunDir, $OutRoot | Out-Null

function UtcNow { (Get-Date).ToUniversalTime().ToString('o') }

function Log {
  param([string]$Message, [string]$Level = 'INFO')
  $line = "[$((Get-Date).ToString('HH:mm:ss.fff'))] [$Level] $Message"
  Write-Output $line
  $script:Transcript.Add($line)
  try { Add-Content -Path $TranscriptPath -Value $line -Encoding utf8 } catch { }
}

function Adb {
  param([string]$Command, [int]$TimeoutSec = 30)
  $line = "[$((Get-Date).ToString('HH:mm:ss.fff'))] adb shell $Command"
  $script:Transcript.Add($line)
  try { Add-Content -Path $TranscriptPath -Value $line -Encoding utf8 } catch { }
  $out = & $Adb -s $Serial shell $Command 2>&1 | Out-String
  $code = $LASTEXITCODE
  $res = [ordered]@{ command = $Command; exit = $code; output = $out.Trim() }
  try {
    Add-Content -Path $TranscriptPath -Value ("  exit=$code out=" + ($out.Trim() -replace "`r?`n", ' | ')) -Encoding utf8
  } catch { }
  return [pscustomobject]$res
}

function AdbRaw {
  param([string[]]$ArgList, [int]$TimeoutSec = 60)
  $line = "[$((Get-Date).ToString('HH:mm:ss.fff'))] adb " + ($ArgList -join ' ')
  $script:Transcript.Add($line)
  try { Add-Content -Path $TranscriptPath -Value $line -Encoding utf8 } catch { }
  $out = & $Adb @ArgList 2>&1 | Out-String
  $code = $LASTEXITCODE
  try { Add-Content -Path $TranscriptPath -Value ("  exit=$code out=" + ($out.Trim() -replace "`r?`n", ' | ')) -Encoding utf8 } catch { }
  return [pscustomobject]@{ exit = $code; output = $out.Trim() }
}

function Invoke-HttpGetStatus {
  param([string]$Url, [int]$TimeoutSec = 5)
  try {
    $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
    return [pscustomobject]@{ ok = $true; status = [int]$r.StatusCode; bytes = $r.Content.Length; error = $null }
  } catch {
    return [pscustomobject]@{ ok = $false; status = $null; bytes = 0; error = "$_" }
  }
}

# ---------------------------------------------------------------------------
# Reference data
# ---------------------------------------------------------------------------

if (-not (Test-Path $RefHashesPath)) {
  Log "reference hashes missing: $RefHashesPath" 'ERROR'
  Write-Output "[X] cannot continue without reference hashes"
  exit 1
}
$RefJson = Get-Content $RefHashesPath -Raw | ConvertFrom-Json
$Reference = [ordered]@{
  'plain.bin'   = [ordered]@{ size = [int]$RefJson.files.'plain.bin'.size;   sha256 = $RefJson.files.'plain.bin'.sha256 }
  'flaky.bin'   = [ordered]@{ size = [int]$RefJson.files.'flaky.bin'.size;   sha256 = $RefJson.files.'flaky.bin'.sha256 }
  'norange.bin' = [ordered]@{ size = [int]$RefJson.files.'norange.bin'.size; sha256 = $RefJson.files.'norange.bin'.sha256 }
}

# ---------------------------------------------------------------------------
# Device helpers
# ---------------------------------------------------------------------------

function Get-AppPid {
  $r = Adb "pidof $Package"
  $txt = $r.output.Trim()
  if ($txt -match '^\d+') { return [int]$txt }
  return $null
}

function Get-PartialSizes {
  if ($script:PartialAccess -eq 'root') {
    $r = Adb "su 0 ls -l /data/data/$Package/files/partials"
  } else {
    $r = Adb "run-as $Package ls -l files/partials"
  }
  $list = New-Object System.Collections.Generic.List[object]
  foreach ($line in ($r.output -split "`n")) {
    $t = $line.Trim()
    if ($t -match '^-') {
      $parts = $t -split '\s+'
      if ($parts.Count -ge 8) {
        $size = 0
        [void][int64]::TryParse($parts[4], [ref]$size)
        $name = ($parts[7..($parts.Count - 1)] -join ' ')
        $list.Add([ordered]@{ name = $name; size = $size })
      }
    }
  }
  return ,$list
}

function Get-FinalFiles {
  # Final outputs are published to the app-specific external dir, but on Android 15 the
  # FUSE view of /sdcard/Android/data/<pkg> is not always traversable from the shell even
  # as root. Try the FUSE path first, then the raw backing store at /data/media/0.
  $roots = @(
    "/sdcard/Android/data/$Package/files",
    "/data/media/0/Android/data/$Package/files"
  )
  $list = New-Object System.Collections.Generic.List[object]
  foreach ($root in $roots) {
    $r = Adb "su 0 ls -lR $root"
    if ($r.exit -ne 0) { continue }
    $currentDir = ''
    foreach ($line in ($r.output -split "`n")) {
      $t = $line.Trim()
      # ls -lR prints a "<dir>:" header before each directory's entries.
      if ($t -match '^(/.*):$') { $currentDir = $Matches[1]; continue }
      if ($t -match '^-') {
        $parts = $t -split '\s+'
        if ($parts.Count -ge 8) {
          $size = 0
          [void][int64]::TryParse($parts[4], [ref]$size)
          $name = ($parts[7..($parts.Count - 1)] -join ' ')
          $path = if ($currentDir) { "$currentDir/$name" } else { $name }
          $list.Add([ordered]@{ name = $name; size = $size; path = $path })
        }
      }
    }
    if ($list.Count -gt 0) { break }
  }
  return ,$list
}

function Clear-AppState {
  Adb "am force-stop $Package" | Out-Null
  $clr = Adb "pm clear $Package"
  Adb "su 0 rm -rf /sdcard/Android/data/$Package/files" | Out-Null
  if ($script:PartialAccess -eq 'root') {
    Adb "su 0 rm -rf /data/data/$Package/files/partials" | Out-Null
  } else {
    Adb "run-as $Package rm -rf files/partials" | Out-Null
  }
  # pm clear revokes runtime grants; the app asks for POST_NOTIFICATIONS on first
  # launch and a pending dialog would block the deep-link flow. Re-grant silently.
  Adb "pm grant $Package android.permission.POST_NOTIFICATIONS" | Out-Null
  Start-Sleep -Milliseconds 400
  return [ordered]@{ pm_clear = $clr.output; pid = (Get-AppPid); post_notifications_granted = $true }
}

function New-DownloadIntent {
  param([string]$File, [int]$RateBytes = 0, [int64]$Expected = 0)
  $url = "http://${FixtureHost}:${FixturePort}/file/$File"
  if ($RateBytes -gt 0) { $url += "?rate=$RateBytes" }
  $enc = [uri]::EscapeDataString($url)
  $intent = "tddl://download?url=$enc&name=$File&expected=$Expected"
  return [ordered]@{ url = $url; intent = $intent; uri = "tddl://download?url=$enc&name=$File&expected=$Expected" }
}

function Start-Download {
  param([string]$Intent)
  # Target the component explicitly: if both a debug and a verify build are installed,
  # an implicit VIEW intent opens the system chooser (ResolverActivity) and the URL is
  # never delivered to the app under test.
  $r = Adb "am start -a android.intent.action.VIEW -d '$Intent' -n $Package/$Activity"
  return $r
}

function Stop-DownloadService {
  $r = Adb "am stopservice -n $Package/$Service"
  return $r
}

function Wait-FinalFile {
  param(
    [string]$Name,
    [int64]$ExpectedSize,
    [int]$TimeoutSeconds = $CompleteTimeoutSeconds,
    [System.Collections.Generic.List[object]]$SampleSink = $null
  )
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $last = $null
  while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    $finals = Get-FinalFiles
    $partials = Get-PartialSizes
    $hit = $finals | Where-Object { $_.name -eq $Name -and $_.size -eq $ExpectedSize } | Select-Object -First 1
    $sample = [ordered]@{
      t_ms = [int]$sw.Elapsed.TotalMilliseconds
      finals = @($finals | ForEach-Object { [ordered]@{ name = $_.name; size = $_.size } })
      partials = @($partials | ForEach-Object { [ordered]@{ name = $_.name; size = $_.size } })
    }
    if ($SampleSink) { $SampleSink.Add($sample) }
    $last = $sample
    if ($hit) {
      return [pscustomobject]@{ found = $true; elapsed_s = [math]::Round($sw.Elapsed.TotalSeconds, 2); path = $hit.path; last_sample = $last }
    }
    Start-Sleep -Milliseconds 800
  }
  return [pscustomobject]@{ found = $false; elapsed_s = [math]::Round($sw.Elapsed.TotalSeconds, 2); path = $null; last_sample = $last }
}

function Pull-And-Hash {
  param([string]$DevicePath, [string]$LocalPath, [int64]$ExpectedSize = 0)
  $pull = AdbRaw @('-s', $Serial, 'pull', $DevicePath, $LocalPath)
  $ok = (Test-Path $LocalPath) -and ((Get-Item $LocalPath).Length -gt 0)
  if ($ok -and $ExpectedSize -gt 0 -and ((Get-Item $LocalPath).Length -ne $ExpectedSize)) { $ok = $false }
  if (-not $ok) {
    # FUSE-restricted external path (or a short pull): read it through root instead.
    if (Test-Path $LocalPath) { Remove-Item $LocalPath -Force -ErrorAction SilentlyContinue }
    & $Adb -s $Serial exec-out "su 0 cat $DevicePath" > $LocalPath 2>$null
    $ok = (Test-Path $LocalPath) -and ((Get-Item $LocalPath).Length -gt 0)
    if ($ok -and $ExpectedSize -gt 0 -and ((Get-Item $LocalPath).Length -ne $ExpectedSize)) {
      return [ordered]@{ pulled = $false; error = "size mismatch after root read: $((Get-Item $LocalPath).Length) != $ExpectedSize"; size = (Get-Item $LocalPath).Length; sha256 = $null }
    }
  }
  if (-not $ok) {
    return [ordered]@{ pulled = $false; error = $pull.output; size = 0; sha256 = $null }
  }
  $f = Get-Item $LocalPath
  return [ordered]@{
    pulled = $true
    path = $LocalPath
    size = $f.Length
    sha256 = (Get-FileHash $LocalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    error = $null
  }
}

function Get-NetState {
  $wifi = (Adb "settings get global wifi_on").output.Trim()
  $data = (Adb "settings get global mobile_data").output.Trim()
  $deep = (Adb "dumpsys deviceidle get deep").output.Trim()
  $addr = (Adb "ip -4 addr show").output.Trim()
  return [ordered]@{
    wifi_on = $wifi
    mobile_data = $data
    deviceidle_deep = $deep
    ipv4 = @($addr -split "`n" | Where-Object { $_ -match 'inet ' } | ForEach-Object { $_.Trim() })
  }
}

function Get-FixtureLogLines {
  if (-not (Test-Path $FixtureLog)) { return @() }
  return @(Get-Content $FixtureLog -ErrorAction SilentlyContinue)
}

function Get-FixtureRequestsSince {
  param([int]$Skip)
  $lines = Get-FixtureLogLines
  if ($lines.Count -le $Skip) { return @() }
  $out = New-Object System.Collections.Generic.List[object]
  foreach ($line in ($lines | Select-Object -Skip $Skip)) {
    if (-not $line -or -not $line.Trim()) { continue }
    try { $out.Add(($line | ConvertFrom-Json)) } catch { }
  }
  return ,$out
}

# ---------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------

function New-Assertion {
  param([string]$Name, $Expected, $Actual, [bool]$Pass, [string]$Detail = '', [bool]$Skipped = $false)
  return [ordered]@{ name = $Name; expected = $Expected; actual = $Actual; pass = $Pass; detail = $Detail; skipped = $Skipped }
}

function Complete-Scenario {
  param($Record)
  $fails = @($Record.assertions | Where-Object { -not $_.pass -and -not $_.skipped })
  $skips = @($Record.assertions | Where-Object { $_.skipped })
  $Record.skipped_assertions = @($skips | ForEach-Object { $_.name })
  if ($fails.Count -eq 0) {
    $Record.verdict = 'PASS'
    $Record.exit_code = 0
  } else {
    $Record.verdict = 'FAIL'
    $Record.exit_code = 2
    $Record.failure_reason = ($fails | ForEach-Object { "$($_.name): expected=$($_.expected) actual=$($_.actual)" }) -join '; '
  }
  $Record.finished_at = UtcNow
  $script:ScenarioRecords.Add($Record)
  Log ("scenario {0} -> {1}" -f $Record.id, $Record.verdict) $(if ($Record.verdict -eq 'PASS') { 'OK' } else { 'FAIL' })
  return $Record
}

# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------

function Scenario-A-ProcessDeath {
  $rec = [ordered]@{
    id = 'a'; name = 'process death mid-download'; started_at = UtcNow
    intent = $null; steps = @(); partial_samples = @(); assertions = @()
    verdict = 'FAIL'; failure_reason = $null; final_files = @(); pulled = @()
    fixture_log_start = 0; fixture_requests = @()
  }
  Log '--- scenario a: process death mid-download ---'
  $clear = Clear-AppState
  $rec.steps += [ordered]@{ step = 'clear_app_state'; at = UtcNow; result = $clear }

  $dl = New-DownloadIntent -File 'plain.bin' -RateBytes 2097152 -Expected $Reference['plain.bin'].size
  $rec.intent = $dl.intent
  $rec.fixture_log_start = (Get-FixtureLogLines).Count
  $start = Start-Download -Intent $dl.intent
  $rec.steps += [ordered]@{ step = 'am start'; at = UtcNow; result = $start }

  $samples = New-Object System.Collections.Generic.List[object]
  Start-Sleep -Milliseconds 1500
  $midPartials = Get-PartialSizes
  $midFinals = Get-FinalFiles
  $pidBefore = Get-AppPid
  $rec.steps += [ordered]@{ step = 'sample_before_kill'; at = UtcNow
    result = [ordered]@{ pid = $pidBefore; partials = $midPartials; finals = $midFinals } }

  $kill = Adb "am force-stop $Package"
  $rec.steps += [ordered]@{ step = 'am force-stop'; at = UtcNow; result = $kill }
  Start-Sleep -Milliseconds 700
  $afterKillPartials = Get-PartialSizes
  $afterKillFinals = Get-FinalFiles
  $pidAfterKillRaw = (Adb "pidof $Package").output.Trim()
  $pidsAfterKill = @($pidAfterKillRaw -split '\s+' | Where-Object { $_ })
  $pidAfterKill = if ($pidsAfterKill.Count -gt 0) { [int]$pidsAfterKill[0] } else { $null }
  $rec.steps += [ordered]@{ step = 'sample_after_kill'; at = UtcNow
    result = [ordered]@{ pidof_raw = $pidAfterKillRaw; pids = $pidsAfterKill; partials = $afterKillPartials; finals = $afterKillFinals } }

  $partialSize = 0
  if ($afterKillPartials.Count -gt 0) {
    foreach ($p in $afterKillPartials) {
      $sz = [int64]$p.size
      if ($sz -gt $partialSize) { $partialSize = $sz }
    }
  }
  $oldPidGone = (-not ($pidsAfterKill -contains [string]$pidBefore))
  $rec.assertions += New-Assertion 'process_was_running_before_kill' 'pid != null' $pidBefore ($null -ne $pidBefore)
  $rec.assertions += New-Assertion 'old_process_terminated' "pid $pidBefore no longer alive" "pidof_after=$pidAfterKillRaw" $oldPidGone
  if ($script:PartialAccess -eq 'none') {
    $rec.assertions += New-Assertion 'partial_exists_after_kill' '0 < size < 8388608' 'unavailable (run-as refused and no adb root)' $true 'partial-size sampling unavailable on this device/build' $true
  } else {
    $rec.assertions += New-Assertion 'partial_exists_after_kill' '0 < size < 8388608' $partialSize ($partialSize -gt 0 -and $partialSize -lt $Reference['plain.bin'].size)
  }

  $relaunch = Start-Download -Intent $dl.intent
  $rec.steps += [ordered]@{ step = 'relaunch_activity'; at = UtcNow; result = $relaunch }
  $wait = Wait-FinalFile -Name 'plain.bin' -ExpectedSize $Reference['plain.bin'].size -TimeoutSeconds $CompleteTimeoutSeconds -SampleSink $samples
  $rec.partial_samples = $samples.ToArray()
  $rec.steps += [ordered]@{ step = 'wait_final'; at = UtcNow; result = [ordered]@{ found = $wait.found; elapsed_s = $wait.elapsed_s; path = $wait.path } }

  # Fixture-side proof that the resumed transfer really used an HTTP Range request.
  $newReqs = Get-FixtureRequestsSince -Skip $rec.fixture_log_start
  $rec.fixture_requests = @($newReqs | ForEach-Object {
    [ordered]@{ seq = $_.seq; path = $_.path; range_header = $_.range_header; status = $_.status
                content_range = $_.content_range; bytes_written = $_.bytes_written } })
  $plainReqs = @($newReqs | Where-Object { $_.path -eq '/file/plain.bin' })
  $rangeReqs = @($newReqs | Where-Object { $_.path -eq '/file/plain.bin' -and $_.range_header -match '^bytes=\d+-' })
  $range206 = @($rangeReqs | Where-Object { $_.status -eq 206 })
  $rangeActual = (($rangeReqs | ForEach-Object { "range=$($_.range_header) status=$($_.status) content_range=$($_.content_range)" }) -join ' | ')
  $rec.assertions += New-Assertion 'plain_requests_seen' '>= 1' $plainReqs.Count ($plainReqs.Count -ge 1)
  $rec.assertions += New-Assertion 'resume_request_used_range' '206 with Range: bytes=N-' $rangeActual ($range206.Count -ge 1)

  $assertFinal = $wait.found
  $sha = $null
  if ($wait.found) {
    $local = Join-Path $RunDir 'a-plain.bin'
    $pulled = Pull-And-Hash -DevicePath $wait.path -LocalPath $local -ExpectedSize $Reference['plain.bin'].size
    $rec.pulled += $pulled
    $sha = $pulled.sha256
    $assertFinal = ($pulled.size -eq $Reference['plain.bin'].size)
  }
  $rec.assertions += New-Assertion 'final_size' $Reference['plain.bin'].size ($(if ($wait.found) { $Reference['plain.bin'].size } else { 0 })) $assertFinal
  $rec.assertions += New-Assertion 'final_sha256' $Reference['plain.bin'].sha256 $sha ($sha -eq $Reference['plain.bin'].sha256)
  return Complete-Scenario $rec
}

function Scenario-B-Doze {
  $rec = [ordered]@{
    id = 'b'; name = 'Doze (deviceidle force-idle)'; started_at = UtcNow
    intent = $null; steps = @(); partial_samples = @(); assertions = @()
    verdict = 'FAIL'; failure_reason = $null; final_files = @(); pulled = @()
  }
  Log '--- scenario b: Doze ---'
  $clear = Clear-AppState
  $rec.steps += [ordered]@{ step = 'clear_app_state'; at = UtcNow; result = $clear }
  $netBefore = Get-NetState
  $rec.steps += [ordered]@{ step = 'net_state_before'; at = UtcNow; result = $netBefore }

  $dl = New-DownloadIntent -File 'plain.bin' -RateBytes 524288 -Expected $Reference['plain.bin'].size
  $rec.intent = $dl.intent
  $start = Start-Download -Intent $dl.intent
  $rec.steps += [ordered]@{ step = 'am start'; at = UtcNow; result = $start }
  Start-Sleep -Seconds 2

  $forceIdle = Adb "dumpsys deviceidle force-idle"
  $rec.steps += [ordered]@{ step = 'deviceidle force-idle'; at = UtcNow; result = $forceIdle }
  Start-Sleep -Seconds 20
  $during = Get-NetState
  $partialsDuring = Get-PartialSizes
  $rec.steps += [ordered]@{ step = 'sample_during_doze'; at = UtcNow
    result = [ordered]@{ net = $during; partials = $partialsDuring } }
  $unforce = Adb "dumpsys deviceidle unforce"
  $rec.steps += [ordered]@{ step = 'deviceidle unforce'; at = UtcNow; result = $unforce }
  Start-Sleep -Seconds 2

  $samples = New-Object System.Collections.Generic.List[object]
  $wait = Wait-FinalFile -Name 'plain.bin' -ExpectedSize $Reference['plain.bin'].size -TimeoutSeconds $CompleteTimeoutSeconds -SampleSink $samples
  $rec.partial_samples = $samples.ToArray()
  $rec.steps += [ordered]@{ step = 'wait_final'; at = UtcNow; result = [ordered]@{ found = $wait.found; elapsed_s = $wait.elapsed_s } }

  $sha = $null
  if ($wait.found) {
    $pulled = Pull-And-Hash -DevicePath $wait.path -LocalPath (Join-Path $RunDir 'b-plain.bin') -ExpectedSize $Reference['plain.bin'].size
    $rec.pulled += $pulled
    $sha = $pulled.sha256
  }
  $rec.assertions += New-Assertion 'completed_after_doze' 'final file present' $wait.found $wait.found
  $rec.assertions += New-Assertion 'final_sha256' $Reference['plain.bin'].sha256 $sha ($sha -eq $Reference['plain.bin'].sha256)
  $rec.assertions += New-Assertion 'device_exited_idle' 'deep=IDLE' $during.deviceidle_deep ($during.deviceidle_deep -match 'IDLE')
  return Complete-Scenario $rec
}

function Scenario-C-NetworkToggle {
  $rec = [ordered]@{
    id = 'c'; name = 'network toggle (wifi+data off then on)'; started_at = UtcNow
    intent = $null; steps = @(); partial_samples = @(); assertions = @()
    verdict = 'FAIL'; failure_reason = $null; final_files = @(); pulled = @()
  }
  Log '--- scenario c: network toggle ---'
  $clear = Clear-AppState
  $rec.steps += [ordered]@{ step = 'clear_app_state'; at = UtcNow; result = $clear }

  $dl = New-DownloadIntent -File 'plain.bin' -RateBytes 1048576 -Expected $Reference['plain.bin'].size
  $rec.intent = $dl.intent
  $start = Start-Download -Intent $dl.intent
  $rec.steps += [ordered]@{ step = 'am start'; at = UtcNow; result = $start }
  Start-Sleep -Seconds 2

  $offWifi = Adb "svc wifi disable"
  $offData = Adb "svc data disable"
  $rec.steps += [ordered]@{ step = 'network_off'; at = UtcNow; result = [ordered]@{ wifi = $offWifi.output; data = $offData.output; net = (Get-NetState) } }
  $partialAtOff = Get-PartialSizes
  Start-Sleep -Seconds 10
  $partialAfterOff = Get-PartialSizes
  $rec.steps += [ordered]@{ step = 'sample_during_outage'; at = UtcNow
    result = [ordered]@{ partials_at_off = $partialAtOff; partials_after_10s = $partialAfterOff; net = (Get-NetState) } }

  $onWifi = Adb "svc wifi enable"
  $onData = Adb "svc data enable"
  $rec.steps += [ordered]@{ step = 'network_on'; at = UtcNow; result = [ordered]@{ wifi = $onWifi.output; data = $onData.output } }
  Start-Sleep -Seconds 3

  $samples = New-Object System.Collections.Generic.List[object]
  $wait = Wait-FinalFile -Name 'plain.bin' -ExpectedSize $Reference['plain.bin'].size -TimeoutSeconds $CompleteTimeoutSeconds -SampleSink $samples
  $rec.partial_samples = $samples.ToArray()
  $rec.steps += [ordered]@{ step = 'wait_final'; at = UtcNow; result = [ordered]@{ found = $wait.found; elapsed_s = $wait.elapsed_s } }
  if (-not $wait.found) {
    $jobs = (Adb "dumpsys jobscheduler | grep -A4 -i $Package").output
    $rec.steps += [ordered]@{ step = 'jobscheduler_state_after_timeout'; at = UtcNow
      result = [ordered]@{ jobs = $jobs; partials = (Get-PartialSizes); pid = (Get-AppPid) } }
  }
  $sha = $null
  if ($wait.found) {
    $pulled = Pull-And-Hash -DevicePath $wait.path -LocalPath (Join-Path $RunDir 'c-plain.bin') -ExpectedSize $Reference['plain.bin'].size
    $rec.pulled += $pulled
    $sha = $pulled.sha256
  }
  $rec.assertions += New-Assertion 'completed_after_network_toggle' 'final file present' $wait.found $wait.found
  $rec.assertions += New-Assertion 'final_sha256' $Reference['plain.bin'].sha256 $sha ($sha -eq $Reference['plain.bin'].sha256)
  return Complete-Scenario $rec
}

function Scenario-D-QueueSurvivesFgsStop {
  $rec = [ordered]@{
    id = 'd'; name = 'queued downloads survive DownloadService stop'; started_at = UtcNow
    intent = $null; steps = @(); partial_samples = @(); assertions = @()
    verdict = 'FAIL'; failure_reason = $null; final_files = @(); pulled = @()
  }
  Log '--- scenario d: queue survives FGS stop ---'
  $clear = Clear-AppState
  $rec.steps += [ordered]@{ step = 'clear_app_state'; at = UtcNow; result = $clear }

  $dl1 = New-DownloadIntent -File 'plain.bin' -RateBytes 1048576 -Expected $Reference['plain.bin'].size
  $dl2 = New-DownloadIntent -File 'norange.bin' -RateBytes 1048576 -Expected $Reference['norange.bin'].size
  $rec.intent = @($dl1.intent, $dl2.intent)
  $rec.steps += [ordered]@{ step = 'am start #1'; at = UtcNow; result = (Start-Download -Intent $dl1.intent) }
  Start-Sleep -Milliseconds 600
  $rec.steps += [ordered]@{ step = 'am start #2'; at = UtcNow; result = (Start-Download -Intent $dl2.intent) }
  Start-Sleep -Seconds 2

  $pidBefore = Get-AppPid
  $partialsBefore = Get-PartialSizes
  $stopSvc = Stop-DownloadService
  $rec.steps += [ordered]@{ step = 'am stopservice'; at = UtcNow
    result = [ordered]@{ pid_before = $pidBefore; partials_before = $partialsBefore; stopservice = $stopSvc } }
  Start-Sleep -Seconds 1
  $pidAfter = Get-AppPid
  $partialsAfter = Get-PartialSizes
  $rec.steps += [ordered]@{ step = 'sample_after_stopservice'; at = UtcNow
    result = [ordered]@{ pid_after = $pidAfter; partials_after = $partialsAfter } }

  # Relaunch the activity (deep link again) so the app can reconcile its queue.
  $relaunch = Start-Download -Intent $dl1.intent
  $rec.steps += [ordered]@{ step = 'relaunch_activity'; at = UtcNow; result = $relaunch }

  $samples = New-Object System.Collections.Generic.List[object]
  $wait1 = Wait-FinalFile -Name 'plain.bin'   -ExpectedSize $Reference['plain.bin'].size   -TimeoutSeconds $CompleteTimeoutSeconds -SampleSink $samples
  $wait2 = Wait-FinalFile -Name 'norange.bin' -ExpectedSize $Reference['norange.bin'].size -TimeoutSeconds $CompleteTimeoutSeconds -SampleSink $samples
  $rec.partial_samples = $samples.ToArray()
  $rec.steps += [ordered]@{ step = 'wait_final_both'; at = UtcNow
    result = [ordered]@{ plain_found = $wait1.found; plain_elapsed_s = $wait1.elapsed_s
                         norange_found = $wait2.found; norange_elapsed_s = $wait2.elapsed_s } }

  $sha1 = $null; $sha2 = $null
  if ($wait1.found) {
    $p1 = Pull-And-Hash -DevicePath $wait1.path -LocalPath (Join-Path $RunDir 'd-plain.bin') -ExpectedSize $Reference['plain.bin'].size
    $rec.pulled += $p1; $sha1 = $p1.sha256
  }
  if ($wait2.found) {
    $p2 = Pull-And-Hash -DevicePath $wait2.path -LocalPath (Join-Path $RunDir 'd-norange.bin') -ExpectedSize $Reference['norange.bin'].size
    $rec.pulled += $p2; $sha2 = $p2.sha256
  }
  $rec.assertions += New-Assertion 'plain_completed'   'final file present' $wait1.found $wait1.found
  $rec.assertions += New-Assertion 'norange_completed' 'final file present' $wait2.found $wait2.found
  $rec.assertions += New-Assertion 'plain_sha256'   $Reference['plain.bin'].sha256   $sha1 ($sha1 -eq $Reference['plain.bin'].sha256)
  $rec.assertions += New-Assertion 'norange_sha256' $Reference['norange.bin'].sha256 $sha2 ($sha2 -eq $Reference['norange.bin'].sha256)
  return Complete-Scenario $rec
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

Log "lifecycle harness start  serial=$Serial  package=$Package  run=$RunStamp"
$preflight = [ordered]@{
  ok = $false; checks = @(); blocked_reason = $null; started_at = UtcNow
}

$adbVersion = (& $Adb version 2>&1 | Out-String).Trim()
$adbOk = ($LASTEXITCODE -eq 0)
$preflight.checks += [ordered]@{ check = 'adb_available'; pass = $adbOk; blocking = $true; detail = (($adbVersion -split "`n") | Select-Object -First 2) -join ' / ' }

$state = AdbRaw @('-s', $Serial, 'get-state')
$devOk = ($state.output.Trim() -eq 'device')
$preflight.checks += [ordered]@{ check = 'device_online'; pass = $devOk; blocking = $true; detail = $state.output.Trim() }

$boot = (Adb 'getprop sys.boot_completed').output.Trim()
$bootOk = ($boot -eq '1')
$preflight.checks += [ordered]@{ check = 'boot_completed'; pass = $bootOk; blocking = $true; detail = $boot }

$pmList = (Adb "pm list packages $Package").output
$pkgOk = ($pmList -match [regex]::Escape("package:$Package"))
$pkgDetail = if ($pkgOk) { "package:$Package installed" } else { "NOT INSTALLED: $Package" }
if ($pkgOk) {
  $pkgMeta = Adb "dumpsys package $Package | grep -E 'versionName|lastUpdateTime' | head -2"
  $pkgDetail = "package:$Package | " + (($pkgMeta.output -split "`n") -join ' ')
}
$preflight.checks += [ordered]@{ check = 'app_installed'; pass = $pkgOk; blocking = $true; detail = $pkgDetail }

# Partial-file sampling needs access to the app's private data dir. Preferred path is
# `run-as` (works for debuggable builds); root is the fallback. The verify build is
# release-signed but built with debuggable=true for the automation harness.
$runAsTest = Adb "run-as $Package id"
$runAsOk = ($runAsTest.output -match 'uid=')
$rootTest = Adb "id"
$rootOk = ($rootTest.output -match 'uid=0\(root\)')
if ($runAsOk) { $script:PartialAccess = 'run-as' } elseif ($rootOk) { $script:PartialAccess = 'root' } else { $script:PartialAccess = 'none' }
$preflight.checks += [ordered]@{ check = 'run_as_available'; pass = $runAsOk; blocking = $false
  detail = $(if ($runAsOk) { ($runAsTest.output -split "`n")[0].Trim() } else { 'run-as refused (not debuggable or not installed for this user)' }) }
$preflight.checks += [ordered]@{ check = 'partial_sampling_access'; pass = ($script:PartialAccess -ne 'none'); blocking = $false
  detail = "method=$($script:PartialAccess) (run-as preferred, adb-root fallback; 'none' means partial sizes cannot be sampled)" }

# The harness can ONLY drive downloads through the debug deep link, so verify the
# installed build actually declares it (an installed APK without the filter cannot
# be scripted at all).
$deepResolve = Adb "cmd package query-activities -a android.intent.action.VIEW -d 'tddl://download'"
$deepOk = ($pkgOk -and ($deepResolve.output -notmatch 'No activities found') -and ($deepResolve.output -match [regex]::Escape($Package)))
$deepDetail = if ($deepOk) {
  (($deepResolve.output -split "`n") | Select-Object -First 2) -join ' / '
} else {
  'installed build does not resolve tddl://download (no VIEW intent-filter). raw: ' + ((($deepResolve.output -split "`n") | Select-Object -First 3) -join ' / ')
}
$preflight.checks += [ordered]@{ check = 'debug_deeplink_resolvable'; pass = $deepOk; blocking = $true; detail = $deepDetail }

# DownloadService is needed for scenario d (non-blocking for a/b/c).
# NOTE: `dumpsys package` does not enumerate services that have no intent filter, so
# verify the component against the INSTALLED APK's manifest with aapt2 instead.
$svcOk = $false
$svcDetail = 'service presence unknown (aapt2 or APK unavailable)'
$checkApk = $ApkPath
if (-not $checkApk -or -not (Test-Path $checkApk)) {
  $pmPathLine = (Adb "pm path $Package").output
  $m = [regex]::Match($pmPathLine, 'package:(\S+?\.apk)')
  if ($m.Success) {
    $checkApk = Join-Path $env:TEMP ("installed-" + $Package + ".apk")
    & $Adb -s $Serial exec-out "su 0 cat $($m.Groups[1].Value)" > $checkApk 2>$null
  }
}
if ($checkApk -and (Test-Path $checkApk) -and (Test-Path $Aapt2)) {
  $manifestText = (& $Aapt2 dump xmltree $checkApk --file AndroidManifest.xml 2>&1 | Out-String)
  $svcOk = $manifestText.Contains($Service)
  $svcDetail = $(if ($svcOk) { "declared in installed manifest: $Service" } else { "NOT declared in installed manifest: $Service" })
}
$preflight.checks += [ordered]@{ check = 'download_service_present'; pass = $svcOk; blocking = $false; detail = $svcDetail }

# Does the installed build launch at all? (informational)
$mainComp = $null
$resolved = (Adb "cmd package resolve-activity --brief $Package").output
foreach ($line in ($resolved -split "`n")) { if ($line -match '/') { $mainComp = $line.Trim(); break } }
if ($mainComp) {
  # A pending POST_NOTIFICATIONS dialog would steal focus on first launch.
  Adb "pm grant $Package android.permission.POST_NOTIFICATIONS" | Out-Null
  $launchProbe = Adb "am start -n $mainComp"
  Start-Sleep -Milliseconds 1500
  $focus = (Adb "dumpsys window | grep -E 'mCurrentFocus' | head -1").output
  $launchOk = ($focus -match [regex]::Escape($Package))
  $preflight.checks += [ordered]@{ check = 'app_launches'; pass = $launchOk; blocking = $false
    detail = $(if ($launchOk) { $focus.Trim() } else { "focus not on app: $($focus.Trim())" }) }
  Adb "am force-stop $Package" | Out-Null
} else {
  $preflight.checks += [ordered]@{ check = 'app_launches'; pass = $false; blocking = $false; detail = 'no launcher activity resolved' }
}

$hostHealth = Invoke-HttpGetStatus "http://127.0.0.1:$FixturePort/health"
$preflight.checks += [ordered]@{ check = 'fixture_server_host'; pass = $hostHealth.ok; blocking = $true; detail = "status=$($hostHealth.status) bytes=$($hostHealth.bytes)" }

# Fresh emulators do not have the static curl/tcpdump helpers; push them on demand.
$hasCurl = ((Adb "su 0 test -x /data/local/tmp/curl; echo rc=\$?").output -match 'rc=0')
$toolsPushed = $false
if (-not $hasCurl -and (Test-Path (Join-Path $ToolsDir 'curl_x86_64'))) {
  AdbRaw @('-s', $Serial, 'push', (Join-Path $ToolsDir 'curl_x86_64'), '/data/local/tmp/curl') | Out-Null
  AdbRaw @('-s', $Serial, 'push', (Join-Path $ToolsDir 'tcpdump_x86_64_android'), '/data/local/tmp/tcpdump') | Out-Null
  Adb "su 0 chmod 755 /data/local/tmp/curl /data/local/tmp/tcpdump" | Out-Null
  $toolsPushed = $true
  $hasCurl = ((Adb "su 0 test -x /data/local/tmp/curl; echo rc=\$?").output -match 'rc=0')
}
if ($toolsPushed) {
  Log 'pushed static curl+tcpdump helpers to /data/local/tmp (fresh emulator)'
}

$devCurl = Adb "su 0 /data/local/tmp/curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://${FixtureHost}:${FixturePort}/health"
$devFixtureOk = ($devCurl.output.Trim() -eq '200')
$preflight.checks += [ordered]@{ check = 'fixture_server_device'; pass = $devFixtureOk; blocking = $true; detail = "http_code=$($devCurl.output.Trim()) via ${FixtureHost}:${FixturePort}" }

$blockingFails = @($preflight.checks | Where-Object { $_.blocking -and -not $_.pass })
$preflight.ok = ($blockingFails.Count -eq 0)
if (-not $preflight.ok) {
  $reasons = New-Object System.Collections.Generic.List[string]
  foreach ($bf in $blockingFails) {
    if ($bf.check -eq 'app_installed') { $reasons.Add("app not installed: $Package") }
    elseif ($bf.check -eq 'debug_deeplink_resolvable') { $reasons.Add('debug deep link tddl://download is not resolvable in the installed build (no matching VIEW intent-filter)') }
    else { $reasons.Add("$($bf.check) failed") }
  }
  $preflight.blocked_reason = ($reasons -join '; ')
}
$preflight.finished_at = UtcNow

$preflightTable = @()
foreach ($c in $preflight.checks) {
  $preflightTable += [pscustomobject]@{ check = $c.check; result = $(if ($c.pass) { 'PASS' } else { 'FAIL' }); blocking = $(if ($c.blocking) { 'yes' } else { 'no' }); detail = $c.detail }
}
Write-Output ''
Write-Output '================ PREFLIGHT ================'
$preflightTable | Format-Table -AutoSize | Out-String | Write-Output
Write-Output ("preflight_ok = {0}   blocked_reason = {1}" -f $preflight.ok, $preflight.blocked_reason)

if (-not $preflight.ok) {
  $report = [ordered]@{
    generated_at = UtcNow; tool = 'scripts/device-lifecycle-test.ps1'; tool_version = '1.0.0'
    serial = $Serial; package = $Package; service = $Service
    run_dir = $RunDir; transcript = $TranscriptPath
    preflight = $preflight
    reference = $Reference
    scenarios = $script:ScenarioRecords.ToArray()
    summary = [ordered]@{ total = 0; passed = 0; failed = 0; skipped = 0 }
    verdict = 'BLOCKED'
    failure_reason = $preflight.blocked_reason
    exit_code = 3
    note = 'Fail-fast is the expected behaviour when the debug APK is not installed yet.'
  }
  $reportPath = Join-Path $RunDir 'lifecycle.json'
  $report | ConvertTo-Json -Depth 12 | Out-File $reportPath -Encoding utf8
  $report | ConvertTo-Json -Depth 12 | Out-File (Join-Path $OutRoot 'latest.json') -Encoding utf8
  Log ("preflight blocked: {0}" -f $preflight.blocked_reason) 'FAIL'
  Write-Output ''
  Write-Output '================ PASS/FAIL ================'
  Write-Output ("BLOCKED  preflight        {0}" -f $preflight.blocked_reason)
  Write-Output ("wrote {0}" -f $reportPath)
  exit 3
}

if ($PreflightOnly) {
  Log 'preflight-only requested; not running scenarios'
  Write-Output 'preflight only: PASS'
  exit 0
}

# ---------------------------------------------------------------------------
# Run scenarios
# ---------------------------------------------------------------------------

$wanted = @('a', 'b', 'c', 'd')
if ($Only.Count -gt 0) {
  $wanted = @($Only | ForEach-Object { $_.ToLowerInvariant() })
}

if ($wanted -contains 'a') { Scenario-A-ProcessDeath | Out-Null }
if ($wanted -contains 'b') { Scenario-B-Doze | Out-Null }
if ($wanted -contains 'c') { Scenario-C-NetworkToggle | Out-Null }
if ($wanted -contains 'd') { Scenario-D-QueueSurvivesFgsStop | Out-Null }

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

$passed = @($script:ScenarioRecords | Where-Object { $_.verdict -eq 'PASS' }).Count
$failed = @($script:ScenarioRecords | Where-Object { $_.verdict -eq 'FAIL' }).Count
$blocked = $script:ScenarioRecords.Count - $passed - $failed
$overall = if ($failed -gt 0) { 'FAIL' } elseif ($passed -gt 0 -and $passed -eq $script:ScenarioRecords.Count) { 'PASS' } else { 'INCOMPLETE' }
$exitCode = if ($failed -gt 0) { 2 } elseif ($overall -eq 'PASS') { 0 } else { 1 }

$report = [ordered]@{
  generated_at = UtcNow; tool = 'scripts/device-lifecycle-test.ps1'; tool_version = '1.0.0'
  serial = $Serial; package = $Package; service = $Service
  run_dir = $RunDir; transcript = $TranscriptPath
  device = [ordered]@{
    adb_version = $adbVersion
    boot_completed = $boot
    android_release = (Adb 'getprop ro.build.version.release').output.Trim()
    sdk = (Adb 'getprop ro.build.version.sdk').output.Trim()
    abi = (Adb 'getprop ro.product.cpu.abi').output.Trim()
  }
  preflight = $preflight
  reference = $Reference
  scenarios = $script:ScenarioRecords.ToArray()
  summary = [ordered]@{ total = $script:ScenarioRecords.Count; passed = $passed; failed = $failed; blocked = $blocked }
  verdict = $overall
  exit_code = $exitCode
}
$reportPath = Join-Path $RunDir 'lifecycle.json'
$report | ConvertTo-Json -Depth 12 | Out-File $reportPath -Encoding utf8
$report | ConvertTo-Json -Depth 12 | Out-File (Join-Path $OutRoot 'latest.json') -Encoding utf8

Write-Output ''
Write-Output '================ PASS/FAIL ================'
$rows = @()
foreach ($s in $script:ScenarioRecords) {
  $rows += [pscustomobject]@{
    scenario = $s.id
    name = $s.name
    result = $s.verdict
    detail = $(if ($s.verdict -eq 'PASS') { 'ok' } else { $s.failure_reason })
  }
}
if ($rows.Count -gt 0) { $rows | Format-Table -AutoSize | Out-String | Write-Output }
Write-Output ("SUMMARY: {0} total, {1} passed, {2} failed -> {3}" -f $script:ScenarioRecords.Count, $passed, $failed, $overall)
Write-Output ("wrote {0}" -f $reportPath)
Write-Output ("transcript {0}" -f $TranscriptPath)
exit $exitCode
