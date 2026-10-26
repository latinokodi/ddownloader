# Performance

Measured, not estimated (skill: `performance-profiling` — measure → analyse → optimise). All numbers
below were produced on this machine: MuMu Player 12, Android 15 (API 35), x86_64, 1080×1920, and
the Android emulator AVD `dd35` (API 35, x86_64).

## APK size

| artifact | size | contents |
|---|---|---|
| `DDownloader-1.0.7-8-release.apk` (R8 + resource shrink) | **2.93 MB** | dex + Compose + 8 bundled OFL font files |
| `DDownloader-1.0.7-8-verify.apk` (same, + test harness) | **3.15 MB** | adds `tddl://` filter + fixture-host exception |
| `DDownloader-1.0.7-8-debug.apk` | ~13 MB | unminified, debug symbols |
| Installed footprint | ≈ 8–9 MB | APK + extracted dex/oat on device |

Breakdown of the release APK (from `aapt2 dump badging` + zip listing): native libs ≈ 40 KB
(two AndroidX helpers), fonts ≈ 1.9 MB, dex ≈ 0.9 MB. **No 7.6 MB Tor binary is shipped** — that
would have been the dominant cost had the anonymity-network scope been kept.

## Cold start (release build, minified)

`adb shell am start -W -n com.ddownloader.android/...MainActivity`:

| device | total time | wait time |
|---|---|---|
| MuMu Player (x86_64, host CPU) | 1.9 s (first launch, incl. onboarding inflation) | 2.0 s |
| Emulator AVD `dd35` (software GPU) | ≈ 2.3 s | ≈ 2.4 s |

These are emulator numbers on a desktop CPU with software rendering; a physical device is faster.
No startup work touches the network (the update check and token validation run in
`viewModelScope`, after first frame).

## Transfer throughput

Fixture downloads over loopback/host with the engine's own throttle: 8 MiB at `rate=2097152`
completed in 4.4–4.7 s (≈ 1.9 MB/s), i.e. the engine adds no measurable overhead versus the
server-imposed rate. Real-network download of a 2.98 MB GitHub release asset took **5.4 s**
(start → verified, including a 302 redirect) on MuMu.

## Known costs / opportunities

| item | cost | note |
|---|---|---|
| Compose + Material3 | ~1 MB dex | acceptable for this UI |
| Bundled fonts (Inter ×4, Space Grotesk ×2, JetBrains Mono ×2) | ~1.9 MB | static instances generated from OFL variable fonts; could be subset to used glyphs (~300 KB win) |
| No baseline profile | first-run JIT | a Baseline Profile module would cut cold start on physical devices |
| Update check | one HTTPS request / 6 h | negligible; runs off the main thread |

## How to reproduce

```powershell
C:\Android\Sdk\platform-tools\adb.exe shell am start -W -n com.ddownloader.android/com.ddownloader.android.ui.MainActivity
Get-ChildItem app\build\outputs\apk -Recurse -Filter *.apk | Select-Object Name,@{n='MB';e={[math]::Round($_.Length/1MB,2)}}
```
