# Pre-release review — v1.0.1

Read-only release-readiness audit (skill: `pre-release-review`), executed on the v1.0.1 build.
Every row states what was checked and the observed result, not an intention.

## Release materials

| item | expected | observed |
|---|---|---|
| Version bumped vs last release | versionCode > 1 | **versionCode 2, versionName 1.0.1** (previous: 1 / 1.0.0) |
| CHANGELOG entry for this version | present | `CHANGELOG.md` → `[1.0.1] - 2026-10-26` |
| Release notes source | same text as changelog | reused verbatim in `gh release create` |
| Signing material available locally | `keystore.properties` + `keystore/release.jks` | present, **not** committed (`.gitignore`) |
| Update feed configured | repo slug correct | `BuildConfig.UPDATE_REPO = "latinokodi/ddownloader"` |

## Build gates

| gate | result |
|---|---|
| `:app:testDebugUnitTest` | 14/14 PASS |
| `:app:lintRelease` | 0 errors, 50 warnings (dependency-version notices) |
| `:app:detekt` | 0 errors, 191 warnings |
| `scripts/verify-release-artifacts.ps1` | 11/11 PASS (signature, zipalign -P 16, 16 KB ELF, manifest hardening, harness absent from release) |
| `scripts/dependency_vuln_scan.py` (OSV) | 133 coordinates → 0 advisories |
| Differential suite on verify APK | 5/5 PASS |
| Lifecycle suite on verify APK | 4/4 PASS |

## Configuration & security posture

| check | observed |
|---|---|
| `allowBackup` | false; cloud-backup **and** device-transfer excluded via `data_extraction_rules.xml` |
| `usesCleartextTraffic` | false; code-level HTTPS interceptor as second guard |
| Storage permissions | none beyond `REQUEST_INSTALL_PACKAGES` (needed by the updater); no `MANAGE_EXTERNAL_STORAGE`, no `WRITE_EXTERNAL_STORAGE` |
| Foreground service type | `dataSync` declared, `onTimeout` overloads handled |
| Debug harness in release | absent (`tddl://` filter only in debug/verify variants) — asserted by the artifact script |
| Secrets in repo | none (scanned tracked text + `git ls-tree` after clean history: no keystore, no `local.properties`) |
| Update trust | download over HTTPS → optional SHA-256 → **signer equality with installed app** required before the installer is invoked |

## Risks accepted for this release

1. **Debrid flows not exercised against a live account** (no credentials available). Mitigation:
   token authorization is validated against the provider endpoints and surfaces Rejected/Unverified;
   the download engine is covered by the differential suite. Residual risk: response-shape drift.
2. **Physical-device coverage** is emulator-only (Android 15 AVD + MuMu Player). 16 KB page
   compliance is proven statically, not on a 16 KB kernel.
3. **`versionCode` restart**: the rename from `pro.tordownloader.android` to `com.ddownloader.android`
   means installs of the pre-rename build (v1.0.0 published briefly under the old package) cannot be
   updated in place — they must be reinstalled once. Acceptable now; must never happen again after
   a real user base exists (recorded in `VERSIONING.md`).

## Verdict

**Ready to publish v1.0.1** subject to the update-path test in `RELEASING.md` §4 being executed
(install v1.0.0 → publish → observe the in-app prompt → install → settings preserved).
