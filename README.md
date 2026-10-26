# DDownloader — Android

Android port of [the Electron app](../tordownloaderElectron) (TorBox + Real-Debrid client with
TMDB/Comet discovery and a resumable local download engine).

**Project path:** `F:\PyApps\ddandroid`

---

## Build & run

Toolchain used and verified here: JDK 21, Android SDK 35 (`platforms;android-35`,
`build-tools;35.0.0`), Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01.

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'          # or edit local.properties
$env:JAVA_HOME    = 'C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'

cd F:\PyApps\ddandroid

# debug build + install + launch
.\gradlew.bat :app:assembleDebug
C:\Android\Sdk\platform-tools\adb.exe install -r -t app\build\outputs\apk\debug\app-debug.apk
C:\Android\Sdk\platform-tools\adb.exe shell am start -n com.ddownloader.android.debug/com.ddownloader.android.ui.MainActivity

# R8-minified, release-signed APK (2.84 MB)
.\gradlew.bat :app:assembleRelease

# release-equivalent build that keeps the test harness (R8 + release signing + tddl:// deep link)
.\gradlew.bat :app:assembleVerify

# checks
.\gradlew.bat :app:testDebugUnitTest     # 14/14 JVM tests (layout parity vs TypeScript, security invariants)
.\gradlew.bat :app:lintRelease           # 0 errors / 50 warnings
.\gradlew.bat :app:detekt                # 0 errors / 191 warnings (report in EVIDENCE/static-analysis)
```

Emulator (already configured here as AVD `dd35`, Pixel 6 / API 35 / x86_64):

```powershell
C:\Android\Sdk\emulator\emulator.exe -avd dd35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on
```

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). Without it the release build falls back to the debug key; the keystore used for the
evidence in this repo is `keystore/release.jks` (self-signed, 4096-bit RSA, 10 000 days).

---

'## Verification status (final build)

| suite | result |
|---|---|
| Differential vs Electron (5 fixture scenarios) | **5/5 PASS** on the R8/signed verify APK |
| Device lifecycle (kill / Doze / network toggle / FGS stop) | **4/4 PASS** |
| JVM unit tests | **14/14 PASS** |
| Release artifacts (signature, zipalign -P 16, 16 KB ELF, manifest hardening) | **11/11 PASS** |
| Lint / detekt / OSV dependency scan | 0 errors / 0 errors / 0 advisories |

## Verification (each script is reproducible)'

```powershell
# 1. Differential vs the Electron downloader: 5 fixture scenarios, byte-identical SHA-256
pwsh -NoProfile -File scripts\device-differential.ps1 -Package com.ddownloader.android.verify

# 2. Lifecycle: kill mid-download / Doze / network toggle / foreground-service stop
pwsh -NoProfile -File scripts\device-lifecycle-test.ps1 -Package com.ddownloader.android.verify

# 3. Release artifacts: apksigner, zipalign -P 16, ELF 16 KB alignment, manifest hardening
pwsh -NoProfile -File scripts\verify-release-artifacts.ps1

# 4. Dependency vulnerabilities (OSV) + egress allowlist
python scripts\dependency_vuln_scan.py
python scripts\verify_egress.py --pcap EVIDENCE\captures\app-launch-2026-10-26.pcap
```

Artifacts land in `EVIDENCE/` (`differential/`, `lifecycle/`, `release/`, `security/`,
`static-analysis/`, `a11y/`, `captures/`).

The fixture server used by the differential/lifecycle tests lives in `test-fixtures/`:

```powershell
node test-fixtures\fixture-server.js --port 8787 --log EVIDENCE\env\fixture-requests-standalone.jsonl
```

The emulator reaches it at `http://10.0.2.2:8787`.

---

## Layout

```
app/src/main/java/pro/tordownloader/android/
  App.kt, ui/MainActivity.kt, ui/AppViewModel.kt
  api/        TorBoxApi, RealDebridApi, CometApi, TmdbApi, Web (HTTP helpers)
  data/       Net (HTTPS-only + per-hop allowlist), CryptoBox (Keystore AES-GCM),
              SettingsRepository (DataStore), DownloadsDb (SQLite), ServiceLocator
  download/   DownloadEngine (Range/resume/verify), DownloadManager (queue),
              DownloadService (foreground, type=dataSync), ResumeWorker (WorkManager)
  storage/    MediaLayout (Jellyfin layout parity port), Storage (SAF/app-files publish)
  core/       AppLog (redacting ring buffer), Fmt
  ui/         theme (design tokens, bundled OFL fonts), icons (Material Icons as ImageVectors),
              components (hero, rings, chips, cards), screens (Search/Discover/Downloads/Settings/Logs)
app/src/test/  MediaLayoutParityTest, SecurityInvariantsTest (+ TypeScript-generated vectors)
app/src/debug/ , app/src/verify/   harness-only overlays (tddl:// deep link, fixture cleartext)
```

Docs: `SPEC.md` (parity matrix), `DECISIONS.md` (Tor/stack/architecture decisions + falsifiers),
`SKILLS_USED.md`, `docs/source-analysis.md` (reverse-engineered original), `docs/ui-inventory.md`,
`docs/parity-draft.md` → `SPEC.md`, `docs/research-{tor,stack}.md`, `docs/ENVIRONMENT.md`.

---

## Known gaps (see SPEC.md for the full matrix)

- Debrid account flows are implemented at the API-client level; the **device-code OAuth UI** is not
  built, so linking an account currently means pasting a token. Live debrid transfers, cloud status
  polling and RD auto file-selection are therefore **unverified** here (no account available).
- The Electron app's 23-engine Python meta-search, subtitle fetch, series rename and FlareSolverr
  Cloudflare bypass are **not ported** (Python/Chromium cannot ship); Comet + TMDB replace the
  discovery/search path.
- i18n (ES/EN) is not ported: the UI is English-only.
