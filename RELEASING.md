# Releasing

Runbook for publishing a build. Derived from the `deployment-procedures` skill (safe sequence,
explicit rollback, verification after the fact) and used for releases v1.0.0 and v1.0.1.

**Key asymmetry to design around:** an APK that has been installed by users cannot be “rolled
back”; a device only accepts a build with a **higher** `versionCode` signed by the **same key**.
Recovery therefore means *shipping a fix with a higher versionCode*, not reverting the release.
That is why the pre-flight steps below are strict and why the update path is tested before
announcing a release.

## 0. Pre-flight (must be green)

```powershell
$env:ANDROID_HOME='C:\Android\Sdk'; $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:detekt --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
pwsh -NoProfile -File scripts\verify-release-artifacts.ps1     # 11/11 expected
python scripts\dependency_vuln_scan.py                        # 0 advisories expected
```

`PRE_RELEASE_REVIEW.md` is filled in for the release being cut.

## 1. Version

`app/build.gradle.kts`: bump `versionCode` (+1 minimum) and `versionName` per `VERSIONING.md`.
Add the `CHANGELOG.md` entry — it becomes the GitHub Release body.

## 2. Build & sign (locally, with the real keystore)

`keystore.properties` + `keystore/release.jks` are **not** in git. Without them the build falls
back to the debug key, which would break the update chain for existing installs.

```powershell
.\gradlew.bat :app:assembleRelease --no-daemon
```

## 3. Verify the artifact before publishing

```powershell
$apk = 'app\build\outputs\apk\release\DDownloader-<version>-<code>-release.apk'
& C:\Android\Sdk\build-tools\35.0.0\apksigner.bat verify --print-certs $apk
& C:\Android\Sdk\build-tools\35.0.0\zipalign.exe -c -P 16 -v 4 $apk
Get-FileHash $apk -Algorithm SHA256
```

The signer digest must equal the previous release's signer (`3f9b918f…83ba` for 1.0.x). A different
signer silently breaks every existing install's update path.

## 4. Test the update path itself (the step everyone skips)

1. Install the **previous** release on a device/emulator.
2. Change a setting (e.g. run one download so the queue has a row).
3. Publish the new release (step 5) as a normal release.
4. Open the old build → it must offer the update → download → verify → install.
5. Confirm: version changed, **Settings / tokens / queue survived**.

## 5. Publish

```powershell
# sha256 sidecar lets the updater verify the download
(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower() | Set-Content "$apk.sha256"
gh release create v1.0.7 $apk "$apk.sha256" `
  --repo latinokodi/ddownloader `
  --title "DDownloader v1.0.1" --notes-file CHANGELOG-snippet.md
```

The in-app updater reads `/releases` and picks the newest non-draft release that is greater than
the running `versionName`. Prereleases are simply “newer but not preferred”.

## 6. Post-release check

- `curl -s https://api.github.com/repos/latinokodi/ddownloader/releases/latest | jq .tag_name`
- Install the published APK on a clean emulator: onboarding must appear, a direct URL download
  must complete with a matching SHA-256 (see `EVIDENCE/differential/`).

## Rollback / hotfix

| situation | action |
|---|---|
| Bad build already published, not yet installed by users | delete the release + tag, fix, re-publish with the **same** versionCode (nobody installed it) |
| Bad build installed by users | publish `x.y.(z+1)` with the fix — never attempt a downgrade |
| Signing key lost | no recovery: every install has to be uninstalled/reinstalled by the user. Back the keystore up |
| Release feed broken (rate limit / API change) | the updater degrades to “update check failed”, downloads still work; fix and publish |
