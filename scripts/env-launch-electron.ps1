# env-launch-electron.ps1 -- launch the original Electron app, capture output,
# sample its TCP connections, then kill it cleanly.
# Owner: env-runner. ASCII only. Does NOT modify the app source.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$App = 'F:\PyApps\tordownloaderElectron'
$Evidence = 'F:\PyApps\ddandroid\EVIDENCE\electron'
$ObserveSeconds = 30
$SampleMs = 500

New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
$outLog = Join-Path $Evidence 'app-launch.log'
$errLog = Join-Path $Evidence 'app-launch.err.log'
$connOut = Join-Path $Evidence 'app-connections.json'
foreach ($f in @($outLog, $errLog, $connOut)) { if (Test-Path $f) { Remove-Item $f -Force } }

$report = [ordered]@{
  started_utc = (Get-Date).ToUniversalTime().ToString('o')
  working_directory = $App
  command = 'npm start   (package.json scripts.start = "electron .")'
  env_notes = [ordered]@{
    VITE_DEV_SERVER_URL = if ($env:VITE_DEV_SERVER_URL) { $env:VITE_DEV_SERVER_URL } else { '<unset>' }
    ELECTRON_RUN_AS_NODE = if ($env:ELECTRON_RUN_AS_NODE) { $env:ELECTRON_RUN_AS_NODE } else { '<unset>' }
    DISPLAY = if ($env:DISPLAY) { $env:DISPLAY } else { '<unset>' }
  }
  process = $null
  electron_pids = @()
  window_or_process_appeared = $false
  samples = @()
  distinct_remote_endpoints = @()
  exit = $null
  cleanup = $null
  error = $null
}

# cmd /c is used so stdout and stderr are separated into files while npm runs.
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm start' -WorkingDirectory $App `
  -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
$report.process = [ordered]@{ pid = $p.Id; name = 'cmd.exe (npm start parent)'; start_time = (Get-Date).ToUniversalTime().ToString('o') }
Write-Output "[i] launched npm start, parent pid=$($p.Id)"

$t0 = Get-Date
$deadline = $t0.AddSeconds($ObserveSeconds)
$seen = @{}
while ((Get-Date) -lt $deadline) {
  $procs = Get-Process -Name electron -ErrorAction SilentlyContinue
  foreach ($pr in $procs) { $seen[$pr.Id] = $true }
  $pids = @($seen.Keys)
  $sample = [ordered]@{ t_ms = [math]::Round(((Get-Date) - $t0).TotalMilliseconds); electron_pids = $pids; connections = @() }
  foreach ($epid in $pids) {
    $conns = Get-NetTCPConnection -OwningProcess $epid -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
      $sample.connections += [ordered]@{
        pid = $epid
        state = [string]$c.State
        local = "$($c.LocalAddress):$($c.LocalPort)"
        remote = "$($c.RemoteAddress):$($c.RemotePort)"
      }
    }
  }
  $report.samples += $sample
  Start-Sleep -Milliseconds $SampleMs
}

$report.electron_pids = @($seen.Keys)
$report.window_or_process_appeared = ($seen.Keys.Count -gt 0)

$remotes = @{}
foreach ($s in $report.samples) { foreach ($c in $s.connections) { if ($c.remote -and $c.remote -notmatch ':0$') { $remotes[$c.remote] = ($remotes[$c.remote] + 1) } } }
$report.distinct_remote_endpoints = @($remotes.Keys | ForEach-Object { [ordered]@{ endpoint = $_; samples = $remotes[$_] } })

# What did it print?
$report.stdout_head = if (Test-Path $outLog) { (Get-Content $outLog -TotalCount 40) } else { @() }
$report.stderr_head = if (Test-Path $errLog) { (Get-Content $errLog -TotalCount 40) } else { @() }
$report.stdout_length = if (Test-Path $outLog) { (Get-Item $outLog).Length } else { 0 }
$report.stderr_length = if (Test-Path $errLog) { (Get-Item $errLog).Length } else { 0 }
$report.parent_still_running = ($null -ne (Get-Process -Id $p.Id -ErrorAction SilentlyContinue))

# Cleanup: kill electron, then npm/cmd parent, then any flaresolverr/python children
$killed = @()
foreach ($pr in (Get-Process -Name electron -ErrorAction SilentlyContinue)) {
  $killed += "electron:$($pr.Id)"; Stop-Process -Id $pr.Id -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Milliseconds 500
foreach ($name in @('flaresolverr', 'python', 'python3')) {
  foreach ($pr in (Get-Process -Name $name -ErrorAction SilentlyContinue)) {
    $killed += "${name}:$($pr.Id)"; Stop-Process -Id $pr.Id -Force -ErrorAction SilentlyContinue
  }
}
if ($null -ne (Get-Process -Id $p.Id -ErrorAction SilentlyContinue)) { $killed += "cmd:$($p.Id)"; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
$report.cleanup = [ordered]@{ killed = $killed; finished_utc = (Get-Date).ToUniversalTime().ToString('o') }
$report.exit = if ($p.HasExited) { $p.ExitCode } else { $null }

$report | ConvertTo-Json -Depth 8 | Out-File $connOut -Encoding utf8
Write-Output "[OK] wrote $connOut"
Write-Output "appeared=$($report.window_or_process_appeared) electron_pids=$($report.electron_pids -join ',')"
Write-Output "killed=$($killed -join ',')"
exit 0
