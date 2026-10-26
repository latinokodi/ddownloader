# env-android.ps1 -- Android toolchain environment for F:\PyApps\ddandroid
# Owner: env-runner. ASCII only. Dot-source it:
#   . F:\PyApps\ddandroid\scripts\env-android.ps1
# It only sets variables / defines helpers; it does not start anything.

$env:JAVA_HOME        = 'C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'
$env:ANDROID_HOME     = 'C:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
$env:PATH             = "C:\Android\Sdk\platform-tools;C:\Android\Sdk\emulator;C:\Android\Sdk\cmdline-tools\latest\bin;$env:PATH"

$global:Adb        = 'C:\Android\Sdk\platform-tools\adb.exe'
$global:Emulator   = 'C:\Android\Sdk\emulator\emulator.exe'
$global:SdkManager = 'C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat'
$global:AvdManager = 'C:\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat'
$global:AvdName    = 'dd35'
$global:EmulatorSerial = 'emulator-5554'

# Start the emulator headless (cold boot, no snapshots) and return the process object.
function Start-ddEmulator {
  param([int]$Port = 5554)
  $log = 'F:\PyApps\ddandroid\EVIDENCE\env\emulator-boot.log'
  $err = 'F:\PyApps\ddandroid\EVIDENCE\env\emulator-boot.err.log'
  $args = @('-avd', $global:AvdName, '-no-window', '-no-audio', '-no-boot-anim',
            '-no-snapshot', '-gpu', 'swiftshader_indirect', '-accel', 'on', '-port', "$Port")
  return Start-Process -FilePath $global:Emulator -ArgumentList $args -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $log -RedirectStandardError $err
}

# Block until sys.boot_completed=1 (condition polling, not a fixed sleep).
function Wait-ddBoot {
  param([string]$Serial = $global:EmulatorSerial, [int]$TimeoutSeconds = 480)
  $t0 = Get-Date
  $deadline = $t0.AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    $state = (& $global:Adb -s $Serial get-state 2>&1 | Out-String).Trim()
    if ($state -eq 'device') {
      $boot = (& $global:Adb -s $Serial shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
      if ($boot -eq '1') {
        return [pscustomobject]@{ Ready = $true; Seconds = [math]::Round(((Get-Date) - $t0).TotalSeconds, 2) }
      }
    }
    Start-Sleep -Milliseconds 1500
  }
  return [pscustomobject]@{ Ready = $false; Seconds = [math]::Round(((Get-Date) - $t0).TotalSeconds, 2) }
}

Write-Output '[OK] Android env set: ANDROID_HOME=C:\Android\Sdk  AVD=dd35  serial=emulator-5554'
