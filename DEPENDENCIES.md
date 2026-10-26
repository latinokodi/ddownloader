# Dependencies & supply chain

Produced with the `dependency-scanning` skill. Trust decisions are recorded, not just versions.

## Runtime dependencies (release classpath)

| dependency | version | why | license |
|---|---|---|---|
| Kotlin stdlib / coroutines | 2.0.21 / 1.9.0 | language + concurrency | Apache-2.0 |
| AndroidX core-ktx, activity-compose, lifecycle-* | 1.15.0 / 1.9.3 / 2.8.7 | platform plumbing | Apache-2.0 |
| Compose BOM (ui, material3, foundation, animation) | 2024.12.01 | UI | Apache-2.0 |
| androidx.work | 2.10.0 | resume after process death / network-constrained retry | Apache-2.0 |
| androidx.datastore-preferences | 1.1.1 | settings store | Apache-2.0 |
| androidx.documentfile | 1.0.1 | SAF output | Apache-2.0 |
| OkHttp + logging-interceptor | 4.12.0 | HTTP, redirect control | Apache-2.0 |
| kotlinx-serialization-json | 1.7.3 | JSON parsing (no codegen) | Apache-2.0 |
| Coil | 2.7.0 | poster thumbnails | Apache-2.0 |
| ~~info.guardianproject:tor-android~~ | — | **removed** with the Tor-network scope (would have added 7.6 MB arm64 and forced compileSdk 37) | — |

Fonts: Inter, Space Grotesk, JetBrains Mono (SIL OFL 1.1) — licence texts in
`app/src/main/assets/licenses/`. Icons: Material Icons (Apache-2.0), generated into `AppIcons.kt`
with provenance in `EVIDENCE/icons/material-icons-paths.json`.

## Scanning

| scan | scope | result |
|---|---|---|
| OSV (Google) via `scripts/dependency_vuln_scan.py` | 133 resolved Maven coordinates on `releaseRuntimeClasspath` | **0 advisories** (`EVIDENCE/security/osv-report.json`) |
| `vulnerability-scanner`'s `security_scan.py` | `app/src` | 0 critical, 0 high, 1 medium (web-security-headers heuristic, N/A for Android) |
| Dependabot | gradle + github-actions, weekly/monthly (`.github/dependabot.yml`) | configured, not yet run (repo new) |

## Supply-chain decisions

1. **No dynamic code at runtime.** No plugin updater, no downloaded scripts, no `exec()`.
   The old Electron app updated its Python plugins over HTTPS; that class of risk is gone.
2. **The update channel is the only runtime-supplied binary**, and it is constrained by HTTPS,
   optional SHA-256, and mandatory signer equality with the installed app.
3. **The signing key never enters the repo or CI.** Releases are signed locally; CI produces an
   unsigned/debug-signed build for testing only. Losing the key would end the update path for all
   installs, so it must be backed up offline.
4. **Dependency updates are PR-gated**, not auto-merged; the CI runs unit tests, lint, detekt and
   the OSV scan on every pull request.
5. **Maven Central only** for binaries; no third-party mirrors (the Guardian Project mirror was
   found stale during research and is not used).
