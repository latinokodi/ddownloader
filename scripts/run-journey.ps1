#requires -Version 5.1
<#
.SYNOPSIS
  Execute an XML-specified Android UI journey (skill: android-ui-journey-testing).

.DESCRIPTION
  Reads journeys/<name>.journey.xml, drives the real app through adb, evaluates the
  assertion attached to each step and writes a machine-readable outcome report.

  Supported actions:
    launch                 start the launcher activity
    tap_text      text     tap the first node whose text/content-desc equals text
    tap_contains  text     same, substring match
    type_text     text     `adb shell input text` into the focused field
    wait          ms       sleep
    assert_text   text     fail unless text/content-desc is present
    assert_version version  fail unless the installed versionName matches
    back / keyevent CODE   send a key

.EXAMPLE
  pwsh -NoProfile -File scripts/run-journey.ps1 -Journey onboarding-smoke -Serial 127.0.0.1:16416
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Journey,
    [string]$Serial = '127.0.0.1:16416',
    [string]$Package,
    [switch]$ClearData,
    [int]$StepTimeoutSeconds = 20
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$xmlPath = if (Test-Path $Journey) { (Resolve-Path $Journey).Path } else { Join-Path $root "journeys\$Journey.journey.xml" }
if (-not (Test-Path $xmlPath)) { throw "journey not found: $xmlPath" }

[xml]$doc = Get-Content $xmlPath -Raw
$pkg = if ($Package) { $Package } else { $doc.journey.package }
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$outDir = Join-Path $root "EVIDENCE\journeys\run-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$results = [System.Collections.Generic.List[object]]::new()
$transcript = [System.Collections.Generic.List[string]]::new()

function Log([string]$m) { $transcript.Add($m); Write-Host $m }

function Adb([string[]]$a) {
    $o = & $adb -s $Serial @a 2>&1
    $transcript.Add(("adb {0} -> {1}" -f ($a -join ' '), ($o -join ' | ')))
    return ($o -join "`n")
}

function Dump-Ui {
    [void](Adb @('shell', 'uiautomator dump /sdcard/_journey.xml >/dev/null 2>&1'))
    $local = Join-Path $outDir 'ui.xml'
    [void](Adb @('pull', '/sdcard/_journey.xml', $local))
    if (Test-Path $local) { return (Get-Content $local -Raw) } else { return '' }
}

function Find-Node([string]$ui, [string]$text, [switch]$Contains) {
    if (-not $ui) { return $null }
    $nodes = [regex]::Matches($ui, '<node[^>]*>')
    foreach ($n in $nodes) {
        $v = $n.Value
        $t = [regex]::Match($v, 'text="([^"]*)"').Groups[1].Value
        $d = [regex]::Match($v, 'content-desc="([^"]*)"').Groups[1].Value
        $hit = if ($Contains) { ($t -like "*$text*") -or ($d -like "*$text*") } else { ($t -eq $text) -or ($d -eq $text) }
        if ($hit) {
            $b = [regex]::Match($v, 'bounds="([^"]*)"').Groups[1].Value
            $m = [regex]::Match($b, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
            if ($m.Success) {
                return [pscustomobject]@{
                    x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
                    y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
                }
            }
        }
    }
    return $null
}

function Wait-Text([string]$text, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Find-Node (Dump-Ui) $text) { return $true }
        Start-Sleep -Milliseconds 600
    }
    return $false
}

Log "journey: $($doc.journey.name)  package=$pkg  serial=$Serial"
$installed = Adb @('shell', "dumpsys package $pkg | grep versionName")
$version = ([regex]::Match($installed, 'versionName=([\w\.\-]+)')).Groups[1].Value
Log "installed version: $version"

if ($ClearData -or ($doc.journey.preconditions.app_data_cleared -ne $null)) {
    [void](Adb @('shell', 'am', 'force-stop', $pkg))
    [void](Adb @('shell', 'pm', 'clear', $pkg))
    # A cleared app re-asks for POST_NOTIFICATIONS; that system dialog would cover the
    # app and make every text assertion fail, so grant it up-front.
    [void](Adb @('shell', 'pm', 'grant', $pkg, 'android.permission.POST_NOTIFICATIONS'))
    Log "app data cleared (notification permission granted)"
}

$failed = 0
foreach ($step in $doc.journey.step) {
    $id = $step.id
    $action = $step.action
    $ok = $true
    $detail = ''

    switch ($action) {
        'launch' {
            [void](Adb @('shell', 'am', 'start', '-n', "$pkg/com.ddownloader.android.ui.MainActivity"))
            Start-Sleep -Milliseconds 1500
        }
        'tap_text' {
            $n = Find-Node (Dump-Ui) $step.text
            if ($n) { [void](Adb @('shell', 'input', 'tap', "$($n.x)", "$($n.y)")); Start-Sleep -Milliseconds 800 }
            else { $ok = $false; $detail = "node '$($step.text)' not found" }
        }
        'tap_contains' {
            $n = Find-Node (Dump-Ui) $step.text -Contains
            if ($n) { [void](Adb @('shell', 'input', 'tap', "$($n.x)", "$($n.y)")); Start-Sleep -Milliseconds 800 }
            else { $ok = $false; $detail = "node containing '$($step.text)' not found" }
        }
        'type_text' {
            [void](Adb @('shell', 'input', 'text', $step.text))
            Start-Sleep -Milliseconds 500
        }
        'wait' { Start-Sleep -Milliseconds ([int]$step.text) }
        'back' { [void](Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK')); Start-Sleep -Milliseconds 600 }
        'keyevent' { [void](Adb @('shell', 'input', 'keyevent', $step.text)) }
        'assert_text' {
            if (-not (Wait-Text $step.text $StepTimeoutSeconds)) {
                $ok = $false; $detail = "expected text '$($step.text)' not present"
            }
        }
        'assert_version' {
            if ($version -ne $step.text) { $ok = $false; $detail = "version '$version' != '$($step.text)'" }
        }
        default { $ok = $false; $detail = "unknown action '$action'" }
    }

    # optional assertion attached to an action step
    if ($ok -and $step.assert_text) {
        if (-not (Wait-Text $step.assert_text $StepTimeoutSeconds)) {
            $ok = $false; $detail = "post-assertion '$($step.assert_text)' not present"
        }
    }

    $results.Add([pscustomobject]@{
            step = $id; action = $action; text = $step.text; note = $step.note
            result = if ($ok) { 'PASS' } else { 'FAIL' }; detail = $detail
        })
    Log ("  step {0,-3} {1,-16} {2} {3}" -f $id, $action, $(if ($ok) { 'PASS' } else { 'FAIL' }), $(if ($detail) { "($detail)" } else { '' }))
    if (-not $ok) { $failed++ }
}

$summary = [ordered]@{
    journey = $doc.journey.name
    package = $pkg
    serial = $Serial
    versionName = $version
    runId = $runId
    steps = $results
    passCount = @($results | Where-Object { $_.result -eq 'PASS' }).Count
    failCount = $failed
    verdict = if ($failed -eq 0) { 'PASS' } else { 'FAIL' }
}
$json = $summary | ConvertTo-Json -Depth 6
Set-Content -Path (Join-Path $outDir 'journey.json') -Value $json -Encoding UTF8
Set-Content -Path (Join-Path $outDir 'adb-transcript.log') -Value $transcript -Encoding UTF8
New-Item -ItemType Directory -Force -Path (Join-Path $root 'EVIDENCE\journeys') | Out-Null
Set-Content -Path (Join-Path $root 'EVIDENCE\journeys\latest.json') -Value $json -Encoding UTF8

Write-Host "`n$($summary.verdict): $($summary.passCount) passed, $($summary.failCount) failed"
Write-Host "report: $outDir\journey.json"
if ($failed -gt 0) { exit 2 } else { exit 0 }
