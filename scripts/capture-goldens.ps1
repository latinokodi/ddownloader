# capture-goldens.ps1 -- capture UI golden screenshots + uiautomator dumps for the
# TorBox Android client, one per bottom-bar destination (Search / Discover /
# Downloads / Settings / Logs).
#
# Owner: env-runner. Does not modify app code.
#
# Navigation strategy: the bottom bar centres were supplied as an estimate
# (x = 180/435/595/795/967 at y = 2178) but this script does NOT use them blindly.
# It dumps the live UI hierarchy first, locates each destination node by
# content-desc / text, computes the centre from its real bounds, taps that, and
# records the node bounds + tap point in goldens.json. Coordinates are only used
# as a documented fallback when a node cannot be found.
#
# Outputs (default):
#   EVIDENCE/screenshots/golden/<name>.png       screenshot per screen
#   EVIDENCE/screenshots/golden/<name>.xml       matching uiautomator hierarchy
#   EVIDENCE/screenshots/golden/goldens.json     metadata (bounds, taps, timings)
#
# ASCII only. PowerShell 7+.

param(
  [string]$Serial  = 'emulator-5554',
  [string]$Package = 'pro.tordownloader.android.verify',
  [string]$OutDir  = 'F:\PyApps\ddandroid\EVIDENCE\screenshots\golden',
  [int]$DensityDpi = 420,
  [int]$SettleMs   = 1200
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$Adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$Screens = @('Search', 'Discover', 'Downloads', 'Settings', 'Logs')
$Fallback = @{
  'Search' = @(180, 2178); 'Discover' = @(435, 2178); 'Downloads' = @(595, 2178)
  'Settings' = @(795, 2178); 'Logs' = @(967, 2178)
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function UtcNow { (Get-Date).ToUniversalTime().ToString('o') }

function AdbShell {
  param([string]$Command)
  return (& $Adb -s $Serial shell $Command 2>&1 | Out-String).Trim()
}

function DumpHierarchy {
  $tmpDevice = '/sdcard/golden-ui.xml'
  AdbShell "uiautomator dump $tmpDevice" | Out-Null
  $local = Join-Path $env:TEMP ("golden-ui-" + [guid]::NewGuid().ToString('N') + '.xml')
  & $Adb -s $Serial pull $tmpDevice $local 2>&1 | Out-Null
  if (-not (Test-Path $local)) { return $null }
  $xml = Get-Content $local -Raw -ErrorAction SilentlyContinue
  Remove-Item $local -Force -ErrorAction SilentlyContinue
  return $xml
}

function Get-UiNodes {
  param([string]$Xml)
  $nodes = New-Object System.Collections.Generic.List[object]
  if (-not $Xml) { return $nodes }
  foreach ($m in [regex]::Matches($Xml, '<node\b[^>]*>')) {
    $tag = $m.Value
    $get = {
      param($name)
      $mm = [regex]::Match($tag, $name + '="([^"]*)"')
      if ($mm.Success) { return $mm.Groups[1].Value }
      return ''
    }
    $bounds = & $get 'bounds'
    $bm = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    $x1 = 0; $y1 = 0; $x2 = 0; $y2 = 0
    if ($bm.Success) {
      $x1 = [int]$bm.Groups[1].Value; $y1 = [int]$bm.Groups[2].Value
      $x2 = [int]$bm.Groups[3].Value; $y2 = [int]$bm.Groups[4].Value
    }
    $nodes.Add([ordered]@{
      class = (& $get 'class')
      text = (& $get 'text')
      desc = (& $get 'content-desc')
      resource_id = (& $get 'resource-id')
      clickable = ((& $get 'clickable') -eq 'true')
      bounds = $bounds
      x1 = $x1; $y1 = $y1; x2 = $x2; y2 = $y2
      cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2)
      w = ($x2 - $x1); h = ($y2 - $y1)
    })
  }
  return $nodes
}

function Find-DestinationNode {
  param($Nodes, [string]$Name)
  $matches = New-Object System.Collections.Generic.List[object]
  foreach ($n in $Nodes) {
    $d = [string]$n.desc
    $t = [string]$n.text
    if ($d -eq $Name -or $t -eq $Name -or $d -like "*$Name*" -or $t -like "*$Name*") { $matches.Add($n) }
  }
  if ($matches.Count -eq 0) { return $null }
  $bottom = @($matches.ToArray() | Where-Object { $_.cy -gt 1800 })
  if ($bottom.Count -gt 0) { return ($bottom | Sort-Object h | Select-Object -First 1) }
  return ($matches.ToArray() | Sort-Object h | Select-Object -First 1)
}

Write-Output "[i] launching $Package"
AdbShell "am force-stop $Package" | Out-Null
AdbShell "pm grant $Package android.permission.POST_NOTIFICATIONS" | Out-Null
AdbShell "am start -W -n $Package/pro.tordownloader.android.ui.MainActivity" | Out-Null
Start-Sleep -Seconds 3
$focus = AdbShell "dumpsys window | grep mCurrentFocus | head -1"
Write-Output "[i] focus: $focus"

$record = [ordered]@{
  generated_at = UtcNow
  tool = 'scripts/capture-goldens.ps1'
  serial = $Serial
  package = $Package
  density_dpi = $DensityDpi
  screens = @()
  note = 'tap points are computed from live uiautomator bounds; hardcoded coordinates are only a documented fallback'
}

foreach ($screen in $Screens) {
  Write-Output "[>] $screen"
  $before = DumpHierarchy
  $nodes = Get-UiNodes -Xml $before
  $node = Find-DestinationNode -Nodes $nodes -Name $screen
  $tapX = $null; $tapY = $null; $how = $null; $nodeInfo = $null
  if ($node) {
    $tapX = $node.cx; $tapY = $node.cy; $how = 'uiautomator-bounds'
    $nodeInfo = [ordered]@{ class = $node.class; text = $node.text; desc = $node.desc
                            resource_id = $node.resource_id; bounds = $node.bounds
                            width_px = $node.w; height_px = $node.h }
  } else {
    $fb = $Fallback[$screen]
    $tapX = $fb[0]; $tapY = $fb[1]; $how = 'fallback-coordinates'
  }

  AdbShell "input tap $tapX $tapY" | Out-Null
  Start-Sleep -Milliseconds $SettleMs

  $xml = DumpHierarchy
  $afterNodes = Get-UiNodes -Xml $xml
  $visible = New-Object System.Collections.Generic.List[string]
  foreach ($n in $afterNodes) {
    if ($n.text -and $n.text.Trim()) { $visible.Add($n.text.Trim()) }
    if ($n.desc -and $n.desc.Trim()) { $visible.Add('[desc]' + $n.desc.Trim()) }
  }
  $visibleUnique = @($visible.ToArray() | Select-Object -Unique)

  $xmlPath = Join-Path $OutDir ("$($screen.ToLowerInvariant()).xml")
  if ($xml) { $xml | Out-File $xmlPath -Encoding utf8 }
  $pngPath = Join-Path $OutDir ("$($screen.ToLowerInvariant()).png")
  & $Adb -s $Serial exec-out screencap -p > $pngPath 2>$null
  $pngSize = if (Test-Path $pngPath) { (Get-Item $pngPath).Length } else { 0 }
  $pngSha = if ($pngSize -gt 0) { (Get-FileHash $pngPath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }

  $record.screens += [ordered]@{
    screen = $screen
    tapped = [ordered]@{ x = $tapX; y = $tapY; method = $how }
    node = $nodeInfo
    xml = $xmlPath
    png = $pngPath
    png_bytes = $pngSize
    png_sha256 = $pngSha
    visible_text = $visibleUnique
    focus_after = (AdbShell "dumpsys window | grep mCurrentFocus | head -1")
    captured_at = UtcNow
  }
  Write-Output "    tap=($tapX,$tapY) via $how  png=$pngSize bytes  visible_texts=$($visibleUnique.Count)"
}

$recordPath = Join-Path $OutDir 'goldens.json'
$record | ConvertTo-Json -Depth 10 | Out-File $recordPath -Encoding utf8
Write-Output "[OK] wrote $recordPath"
Write-Output ("[OK] screens: " + (($record.screens | ForEach-Object { $_.screen }) -join ', '))
