#requires -Version 5.1
# Verify: (1) English onboarding, (2) language toggle to Spanish, (3) URL+code link dialog.
$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$serial = '127.0.0.1:16416'
$pkg = 'com.ddownloader.android'
$root = 'F:\PyApps\ddandroid'
$shots = Join-Path $root 'EVIDENCE\screenshots'

function Adb { & $adb -s $serial @args 2>&1 }
function Dump {
    Adb shell "uiautomator dump /sdcard/v.xml >/dev/null 2>&1" | Out-Null
    $tmp = Join-Path $env:TEMP 'verify-ui.xml'
    Adb pull /sdcard/v.xml $tmp | Out-Null
    if (Test-Path $tmp) { return (Get-Content $tmp -Raw) }
    return ''
}
function Texts($ui) { [regex]::Matches($ui, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique }
function TapText([string]$text, [switch]$Contains) {
    $ui = Dump
    $nodes = [regex]::Matches($ui, '<node[^>]*>')
    foreach ($n in $nodes) {
        $v = $n.Value
        $tx = [regex]::Match($v, 'text="([^"]*)"').Groups[1].Value
        $ds = [regex]::Match($v, 'content-desc="([^"]*)"').Groups[1].Value
        $hit = if ($Contains) { ($tx -like "*$text*") -or ($ds -like "*$text*") } else { ($tx -eq $text) -or ($ds -eq $text) }
        if ($hit) {
            $b = [regex]::Match($v, 'bounds="(\[(\d+),(\d+)\]\[(\d+),(\d+)\])"')
            if ($b.Success) {
                $x = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)
                $y = [int](([int]$b.Groups[3].Value + [int]$b.Groups[5].Value) / 2)
                Adb shell "input tap $x $y" | Out-Null
                Start-Sleep -Milliseconds 1200
                return $true
            }
        }
    }
    return $false
}
function Shot([string]$name) {
    Adb exec-out screencap -p > (Join-Path $shots $name)
    "{0}: {1} bytes" -f $name, (Get-Item (Join-Path $shots $name)).Length
}

Adb shell "pm clear $pkg" | Out-Null
Adb shell "pm grant $pkg android.permission.POST_NOTIFICATIONS" | Out-Null
Adb shell "am start -n $pkg/com.ddownloader.android.ui.MainActivity" | Out-Null
Start-Sleep -Seconds 7

$ui = Dump
$en = Texts $ui
Write-Host "=== [1] onboarding (English) ==="
$en | Select-Object -First 10 | ForEach-Object { "   $_" }
Shot 'i18n-1-onboarding-en.png'

# go to the debrid page and open the link dialog
if (TapText 'Continue') {
    Start-Sleep -Milliseconds 800
    $ui = Dump
    Write-Host "=== [2] debrid page ==="
    (Texts $ui | Select-Object -First 12) | ForEach-Object { "   $_" }
    Shot 'link-1-debrid-page.png'
    if (TapText 'Link') {
        Start-Sleep -Seconds 6
        $ui = Dump
        Write-Host "=== [3] link dialog (device code) ==="
        (Texts $ui | Select-Object -First 12) | ForEach-Object { "   $_" }
        Shot 'link-2-code-dialog.png'
        # remember the 6-digit code if present
        $code = [regex]::Match($ui, 'text="(\d{6})"').Groups[1].Value
        $url = [regex]::Match($ui, 'text="(https://[^"]+)"').Groups[1].Value
        Write-Host "   issued code: '$code'  url: '$url'"
        Adb shell "input keyevent KEYCODE_BACK" | Out-Null
        Start-Sleep -Milliseconds 800
    }
}

# skip the assistant and switch language
TapText 'Skip' | Out-Null
Start-Sleep -Seconds 2
if (TapText 'Settings') {
    Start-Sleep -Seconds 2
    $ui = Dump
    Write-Host "=== [4] settings (English) ==="
    (Texts $ui | Select-Object -First 10) | ForEach-Object { "   $_" }
    if (TapText 'Español') {
        Start-Sleep -Seconds 3
        $ui = Dump
        Write-Host "=== [5] settings after toggling to Spanish ==="
        (Texts $ui | Select-Object -First 14) | ForEach-Object { "   $_" }
        Shot 'i18n-2-settings-es.png'
    } else { Write-Host "   (Español option not found)" }
}
