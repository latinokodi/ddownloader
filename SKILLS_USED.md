# SKILLS_USED — 21 skills, and what each one actually changed

Requirement: ≥20 skills from `F:\PyApps\agent-skills\.agent\skills`, chosen by reading their
`SKILL.md`, with the decision or artifact each one changed. A skill is only listed if its output is
traceable to a file in this repository; "read but not applied" is never counted.

Two groups: **Team-applied** (a teammate read and applied the skill; the artifact is verifiable in
the tree) and **Lead-applied** (I read the `SKILL.md` and made the change myself).

| # | skill (path under `.agent/skills/`) | applied by | decision / artifact it changed |
|---|---|---|---|
| 1 | `powershell-windows` | env-runner | `scripts/env-android.ps1`, `scripts/env-boot-wait.ps1`: ASCII-only log markers, parenthesised cmdlets, `-Depth` JSON, null-safe property access. Directly prevented a `$pid`-is-read-only bug that had left `tcpdump` running. |
| 2 | `systematic-debugging` | env-runner | `EVIDENCE/env/environment.json` + `docs/ENVIRONMENT.md` §: hypothesis→test isolation of the `adb reverse`/`forward` failure (registers but delivers 0 payload), outcome recorded as `network.adb_reverse/forward`, decision to standardise on `10.0.2.2`. |
| 3 | `testing-patterns` | env-runner | `test-fixtures/fixture-server.js` + `test-fixtures/electron-diff.js`: factory-generated deterministic bytes (`makeBytes`/`seedFor`), data-driven `runScenario(spec)`, isolated per-scenario workspaces, exact-equality reference assertions. This harness is what makes the differential test an oracle rather than a smoke test. |
| 4 | `android_ui_verification` | env-runner | `EVIDENCE/screenshots/emulator-boot.png` + `emulator-home-ui.xml`; calibration (`wm size` 1080×2400/420 dpi) reused for the golden-screenshot + a11y audits. |
| 5 | `lint-and-validate` | env-runner | Validation gate before reporting: `node --check` on both harness scripts, ESM import check of the shim, `Parser::ParseFile` on both `.ps1` files, `ConvertFrom-Json` on every JSON output. Recorded in `EVIDENCE/electron/run-log.txt`. |
| 6 | `protocol-reverse-engineering` | env-runner | `scripts/pcap_parse.py` (pcap+pcapng, Ethernet/VLAN/SLL/SLL2/RAW, IPv4/IPv6, TCP/UDP, DNS, TLS SNI from reassembled streams) and `EVIDENCE/captures/CAPTURE-MANIFEST.json`; capture→decode→document→validate workflow. |
| 7 | `full-output-enforcement` | source-analyst | `docs/source-analysis.md`: all **47** IPC channels enumerated and a **63-row** constants table instead of representative samples; all 40 parity rows completed with no "etc." placeholders. |
| 8 | `api-documentation` | source-analyst | `docs/source-analysis.md` §2/§4: per-channel arguments, return shape, error columns and error-mapping subsections for TorBox/Real-Debrid; auth-scheme table. Used directly to write `api/TorBoxApi.kt` + `api/RealDebridApi.kt`. |
| 9 | `documentation-templates` | source-analyst | §2.1 table schema (parameters/response/error) and the ADR template that seeded the parity-matrix columns; progressive-disclosure reading paths in §0.1. |
| 10 | `docs-architect` | source-analyst | Restructured `source-analysis.md` into exec-summary → architecture → data → integrations → security → appendix with explicit "why" rationale (own-files scoping §5.8, allowlist purpose §6.2). |
| 11 | `code-documentation-code-explain` | source-analyst | §5.2–5.9 numbered step walkthroughs of the download engine plus a §5.11 pitfalls table — the specification `download/DownloadEngine.kt` was written against. |
| 12 | `architecture-decision-records` | source-analyst + evidence-researcher | `DECISIONS.md` structure (context → options → consequence), the scored option tables, and the explicit "falsifiers" section. |
| 13 | `deep-research` | evidence-researcher | Turned Arti from "promising" into **gated**: found `doc/Android.md` last touched 2022-04-20 and the sample project abandoned 2022-08-19, which is why Arti was rejected on feasibility rather than preference (`docs/research-tor.md`). |
| 14 | `supply-chain-security` | evidence-researcher | Found the **stale Guardian Project maven mirror** (metadata `latest 0.4.8.16`, 2025-05-16) that the upstream README recommends, and the exact transitive dependency set. Action: `settings.gradle.kts` now resolves from Maven Central only — the mirror block was removed. |
| 15 | `security-and-hardening` | evidence-researcher | Established the fail-closed requirement that shaped `data/Net.kt`: HTTPS-only + per-hop allowlist + no silent downgrade. (Its Tor-SOCKS specifics were dropped when the owner corrected the "tor = TorBox" premise; the fail-closed principle survived.) |
| 16 | `privacy-by-design` | evidence-researcher | "No magnet / API-key logging" requirement → `core/AppLog.kt` redaction rules and `SecurityInvariantsTest` assertions; secrets-at-rest requirement → `data/CryptoBox.kt`. |
| 17 | `mobile-design` | **lead** | Applied its MFRI/touch-first rules: computed MFRI for the app (Platform Clarity 5, Accessibility Readiness 3 − Interaction 2 − Performance 3 − Offline 1 = **+2 → "add validation"**), then fixed the resulting touch-target violation — `GhostButton` chips were ~36 dp tall, now `heightIn(min = 44.dp)` (`ui/components/Components.kt`). Also removed the desktop-style always-on animation. |
| 18 | `ui-a11y` | **lead** | Its 4.5:1 contrast rule caught a real defect: `TdColor.TextLow` `#5F6A7D` was ≈3.6:1 on `Ink0`. Changed to `#8590A3` (≈6.1:1) in `ui/theme/Theme.kt`. Its "icons need a label" rule: the bottom-nav Icon had `contentDescription = t.label` **and** an adjacent Text label (double announcement) → `contentDescription = null` + `Role.Tab` + `selected` semantics in `ui/MainActivity.kt`. Its motion rule → `rememberReduceMotion()` honouring `ANIMATOR_DURATION_SCALE`. |
| 19 | `mobile-security-coder` | **lead** | Its input-validation / storage checklist produced: URL scheme + userinfo validation in `AppViewModel.addUrl` (rejects `file://`, embedded credentials); orphaned `.part` cleanup (`Storage.pruneOrphans`, called from `ServiceLocator.init`); confirmation that no WebView exists, backups are excluded, and all SQL uses `ContentValues`/bound args (no string-built SQL). |
| 20 | `clean-code` | **lead** | Its "intention-revealing names / one level of abstraction" rules removed the `Net_isLoopback()`/`Net_host()` convenience wrappers in `download/DownloadManager.kt` (a shortcut taken mid-debug) and replaced them with a documented `hostOf(url)` helper plus a local `loopback` value. Also moved the multi-page retry/verify logic out of the transfer loop into named branches. |
| 21 | `vulnerability-scanner` | **lead** | Ran the skill's own `scripts/security_scan.py` against `app/src` → `EVIDENCE/security/security-scan-appsrc.txt` + `.json` (**0 critical, 0 high**; 1 medium = a web-security-headers heuristic that is not applicable to an Android client). Its OWASP/supply-chain checklist is also the frame for `scripts/dependency_vuln_scan.py` (OSV: 133 coordinates, 0 advisories). |

## Skills deliberately read and rejected (not counted)

- `customs`/`web-artifacts-builder`/`frontend-design`/`gsap-*` — web/HTML targets, no artifact here.
- `tdd-workflow` — the parity oracle (TypeScript-generated vectors) was written **after** the port
  rather than test-first; claiming TDD would be inaccurate, so the two test files it influenced are
  attributed to the tests themselves, not to the skill.
- `git-commit` — no `SKILL.md` at the advertised path (`git-commit/SKILL.md` missing).
- Tor-network-specific candidates (`security-arsenal`, `linux-hardening`, …) — out of scope after the
  owner's correction.

## Cross-check

Distinct skills: 6 (env-runner) + 6 (source-analyst) + 5 (evidence-researcher, one shared with
source-analyst) + 5 (lead) = **21 distinct**, each with a file-level artifact above.


---

# Second audit — 10 additional skills (release / update / quality phase)

Re-audited `F:\PyApps\agent-skills\.agent\skills` against the project as it stood after the
DDownloader rename and the self-update requirement, and picked ten skills that had not been used.
Each row names the artifact the skill changed — none were read-only.

| # | skill | what it changed |
|---|---|---|
| 22 | `github-actions` | Added `.github/workflows/android-ci.yml` (JDK 21 → unit tests → lint → detekt → verify/debug APKs → OSV scan → artifact upload) and `.github/dependabot.yml` (weekly Gradle, monthly Actions). |
| 23 | `semantic-versioning` | Wrote `VERSIONING.md` with an arithmetic `versionCode = MAJOR*10000 + MINOR*100 + PATCH` rule and a bump checklist; applied it: 1.0.0/1 → 1.0.1/2 → 1.0.2/3. |
| 24 | `changelog-automation` | Created `CHANGELOG.md` (Keep a Changelog, user-facing phrasing) and reused its entries verbatim as GitHub Release notes instead of writing ad-hoc text. |
| 25 | `deployment-procedures` | Wrote `RELEASING.md`: pre-flight gates, local signing (keystore never in CI), artifact verification, **update-path test before announcing**, publish, and a rollback table that acknowledges an APK cannot be downgraded. |
| 26 | `pre-release-review` | Produced `PRE_RELEASE_REVIEW.md` — a read-only go/no-go audit with observed values (version bump, signing, manifest hardening, permissions, secrets, CI gates) and three explicitly accepted risks. |
| 27 | `threat-modeling` | Wrote `THREAT-MODEL.md` (STRIDE) covering assets, five trust boundaries and the **update channel** specifically (signer equality, per-hop allowlist, checksum) plus what is out of scope. |
| 28 | `performance-profiling` | Wrote `PERFORMANCE.md` with measured data: APK sizes (2.84/3.09/12.97 MB), cold-start `am start -W` numbers, real-network 2.98 MB download timing, and the font-subsetting opportunity. |
| 29 | `dependency-scanning` | Wrote `DEPENDENCIES.md` (runtime dependency table with licences, scan results, supply-chain decisions) and wired the OSV scan + Dependabot into CI. |
| 30 | `android-ui-journey-testing` | Added `journeys/onboarding-smoke.journey.xml` (13 steps with assertions) and `scripts/run-journey.ps1`, an XML-driven adb executor that writes `journey.json`; it immediately found a real harness gap (the POST_NOTIFICATIONS dialog covering the app after `pm clear`). |
| 31 | `backup-recovery` | Implemented `data/SettingsBackup.kt` + Settings → “Backup & restore” (SAF export/import of non-secret settings; tokens deliberately excluded) so a reinstall or new device does not lose configuration. |

Also fixed while applying these: every blocking network/IO call in `UpdateChecker`, `AccountAuth`
and `SettingsBackup` now runs on `Dispatchers.IO` (the first device run threw
`NetworkOnMainThreadException`), and the hero “Downloads” button now navigates.

## Verification status of this batch

| item | status |
|---|---|
| CI workflow, changelog, versioning, releasing, pre-release review, threat model, performance, dependencies | written and committed (CI not yet executed on GitHub) |
| Backup & restore | implemented, unit-compiled; **not exercised on device** |
| Journey runner | implemented and executed; found the permission-dialog gap; the suite itself was not yet green when the session stopped |
| Self-update | implemented (feed → download → SHA-256 → signer check → installer), v1.0.1 and v1.0.2 published; **the in-app prompt did not appear on the emulator in the last test — unresolved** |
