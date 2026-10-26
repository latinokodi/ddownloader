# FINAL REPORT — DDownloader Android

**Project path:** `F:\PyApps\ddandroid`
**Baseline ported from:** `F:\PyApps\tordownloaderElectron` (commit `1f70fd4` + working tree + the
2026-10-26 Comet add-on replacement)

> **Premise correction (owner, 2026-10-26):** "tor" in this project means **TorBox**, the debrid
> service — not the Tor anonymity network. The original app contains no onion-routing code
> (verified by grep: `docs/source-analysis.md` §6). The Tor-network scope the brief assumed was
> removed from the port and documented in `DECISIONS.md` (Decision A). "Leak-free" is applied where
> it actually belongs: API credentials, tokens in logs, and data at rest.

---

## 1. Build & run

```powershell
$env:ANDROID_HOME='C:\Android\Sdk'; $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'
cd F:\PyApps\ddandroid

.\gradlew.bat :app:assembleDebug            # 12.97 MB debug APK
.\gradlew.bat :app:assembleRelease          # 2.84 MB, R8 + resource shrinking + release signing
.\gradlew.bat :app:assembleVerify           # 3.09 MB, release-equivalent + test harness
.\gradlew.bat :app:testDebugUnitTest        # 14 JVM tests
.\gradlew.bat :app:lintRelease              # 0 errors
.\gradlew.bat :app:detekt                   # 0 errors

C:\Android\Sdk\platform-tools\adb.exe install -r -t app\build\outputs\apk\debug\app-debug.apk
C:\Android\Sdk\platform-tools\adb.exe shell am start -n com.ddownloader.android.debug/com.ddownloader.android.ui.MainActivity
```

Verification (each reproducible, artifacts under `EVIDENCE/`):

```powershell
pwsh -NoProfile -File scripts\device-differential.ps1 -Package com.ddownloader.android.verify
pwsh -NoProfile -File scripts\device-lifecycle-test.ps1 -Package com.ddownloader.android.verify
pwsh -NoProfile -File scripts\verify-release-artifacts.ps1
python scripts\dependency_vuln_scan.py
python scripts\verify_egress.py --pcap EVIDENCE\captures\app-launch-2026-10-26.pcap
python scripts\pcap_parse.py --pcap EVIDENCE\captures\app-launch-2026-10-26.pcap --out flows.json
```

---

## 2. Requirement-by-requirement result

Every row states the oracle (what independent evidence decided it), not just the implementation.

| # | Requirement | Verdict | Evidence | Observed result |
|---|---|---|---|---|
| 1 | **Step 0 — environment**: read source, run Gradle/adb, launch an emulator | **Verified** | `docs/ENVIRONMENT.md`, `EVIDENCE/env/environment.json` | SDK installed from scratch (cmdline-tools 136 MB → platform-tools 37.0.1, platforms;android-35, build-tools 35.0.0, emulator 37.2.12, system-image API 35 x86_64); AVD `dd35` boots to `sys.boot_completed=1` in **45.5 s**, `adb root` works, 11 GB free. Gradle 8.11.1 wrapper → APKs built. |
| 2 | **Step 1 — ground truth from the original** | **Verified** | `docs/source-analysis.md` (1,103 lines), `docs/ui-inventory.md`, `docs/parity-draft.md`, `EVIDENCE/electron/*` | Electron app launched (window, worker, FlareSolverr on :8191, CF bridge :56042); live sockets observed to real-debrid/torbox endpoints; 47 IPC channels, 63 constants, 12 settings fields, 12 layout vectors documented. The shipped downloader was then run against a byte-deterministic fixture server: 5 scenarios, SHA-256 reference values recorded (`reference-hashes.json`). |
| 3 | **Step 1 — traffic capture** | **Verified** | `EVIDENCE/captures/guest-traffic-2026-10-26.pcap` (2,423,924 B, 3,129 pkts, sha256 `d7fdb7fe…31bec`), `guest-traffic-2026-10-26.flows.json`, `CAPTURE-MANIFEST.json` | Captured **inside the guest** with a pushed static tcpdump 4.9.2 (host `pktmon` needs elevation and was denied — recorded as a blocker that was routed around, not a claim). stdlib parser decodes pcap/pcapng, Ethernet/VLAN/SLL/SLL2/RAW, IPv4/IPv6, TCP/UDP, DNS, HTTP and TLS SNI from **reassembled** streams; validated by finding the known `check.torproject.org` SNI/DNS/Host. |
| 4 | **Step 1 — logs + SHA-256 of downloaded test files** | **Verified** | `EVIDENCE/electron/app-launch.log`, `run-log.txt`, `reference-hashes.json`, `diff-electron.json` | Electron run downloaded: s1 8,388,608 B sha `7ea22e5b…41c56`; s2 resume from 3,145,728 B → same sha; s3 range-ignored keeps partial; s4/s5 exposed two **real defects** (promise never resolves on mid-stream cut and on 0-byte body). |
| 5 | **Step 2 — evidence-based Tor decisions** | **Verified (decision recorded; scope later corrected)** | `docs/research-tor.md` (36.8 KB, primary sources + parsed AAR), `EVIDENCE/research/artifacts/` | Embedded `tor-android 0.4.9.14` was recommended over Orbot/Arti on maintenance + self-containment, with an empirical 16 KB `p_align` parse. **Owner correction:** Tor network is out of scope. Recorded, not silently dropped: `DECISIONS.md` Decision A + falsifiers. |
| 6 | **Step 2 — evidence-based stack decision** | **Verified** | `docs/research-stack.md` (25.9 KB), `DECISIONS.md` Decision B | Kotlin+Compose **480/500** vs Flutter 385, RN 340, Capacitor 260, scored on leak control, background/resume, APK size, bridge, maintenance, license; runner-up and the evidence that would flip it documented. Capacitor disqualified by its own docs (10-min background cap, no SOCKS). |
| 7 | **Step 3 — foreground service with correct type + timeout handling** | **Verified (type/manifest) / Supported (runtime path)** | `download/DownloadService.kt`, artifact check `foregroundServiceType declared` | `dataSync` FGS with `POST_NOTIFICATIONS`, progress notification, Pause/Stop actions; Android 15 `onTimeout(startId)` **and** `onTimeout(startId, fgsType)` pause the queue and persist, so the next start resumes. Screenshot of the system notification during transfer in `EVIDENCE/screenshots/`. |
| 8 | **Step 3 — resumable downloads (HTTP Range)** | **Verified** | `EVIDENCE/differential/run-20261026T145849Z/differential.json` | s2: killed at ~4 MB → relaunch → server log shows `Range: bytes=4325376-` → **206** `Content-Range: bytes 4325376-8388607/8388608` → final 8,388,608 B, sha `7ea22e5b…41c56` = Electron reference. |
| 9 | **Step 3 — survival of process death** | **Verified** | same run + `EVIDENCE/lifecycle/` | Force-stop mid-transfer then relaunch resumed from the file length, not from zero (logcat + fixture request log). `ResumeWorker` (WorkManager) re-arms the durable queue after death/reboot. |
| 10 | **Step 3 — scoped-storage output** | **Verified** | manifest (no storage permissions), `storage/Storage.kt`, artifact check | Partials in app-private storage; publish to `<external-files>/Download/...` or a user-picked SAF tree. No `WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`. |
| 11 | **Step 3 — notification permission** | **Verified** | `ui/MainActivity.kt` runtime request; `EVIDENCE/screenshots/app-first-launch.png` | Android 13+ `POST_NOTIFICATIONS` prompt shown on first launch; denial does not stop transfers (documented). |
| 12 | **Step 3 — cleartext disabled** | **Verified** | `res/xml/network_security_config.xml`, artifact check, `data/Net.kt` | `usesCleartextTraffic=false` + base-config `cleartextTrafficPermitted=false`; a second code-level interceptor refuses non-HTTPS. The only exceptions are loopback and — **debug/verify builds only** — the emulator fixture host `10.0.2.2`. Release APK verified to contain no harness (`tddl` absent). |
| 13 | **Step 3 — no backups of sensitive data** | **Verified** | manifest + `res/xml/data_extraction_rules.xml`, artifact checks | `allowBackup=false`, `fullBackupContent=false`, and explicit cloud-backup **and** device-transfer exclusions for all domains; tokens additionally AES-256-GCM encrypted with a non-exportable AndroidKeyStore key. |
| 14 | **Step 4 — differential vs Electron (same URLs → identical SHA-256, same resume)** | **Verified** | `EVIDENCE/differential/run-20261026T145849Z` (on the **R8-minified, release-signed** verify APK) | **5/5 PASS**: s1 fresh sha match; s2 resume sha match with 206 + Range; s3 server-ignores-Range handled *better* than Electron (restart-from-zero, correct sha instead of publishing a truncated file); s4 flaky-cut recovers with a Range resume and sha match; s5 0-byte body fails as truncation, nothing published. |
| 15 | **Step 4 — lifecycle: kill mid-download / Doze / network toggle / FGS stop** | **Verified** | `EVIDENCE/lifecycle/run-20261026T152202Z/lifecycle.json` (final build) | **4/4 PASS.** (a) force-stopped at 2.6 MB → relaunch issued `Range: bytes=2621440-` → 206 → final sha `7ea22e5b…41c56`; (b) `deviceidle force-idle` 20 s → completed, sha match; (c) wifi+data off 10 s → **initially FAILED**, root-caused to no retry on reconnect, fixed (`NetworkMonitor` + network-constrained `WorkManager` retry + 30 s read timeout) → now PASS with the log trace `connectivity restored — retrying 1 interrupted transfer(s)` → completed sha match; (d) `am stopservice` FGS → both queued transfers completed, both shas match. |
| 16 | **Step 4 — release build (R8, signed) runs the suite** | **Verified** | `scripts/verify-release-artifacts.ps1` → `EVIDENCE/release/artifacts.json`; differential on `com.ddownloader.android.verify` | Verify variant = same R8 + resource shrinking + release keystore; differential 5/5 on it. `apksigner verify` OK (v1/v2/v3), signer SHA-256 `3f9b918f…83ba`. |
| 17 | **Step 4 — apksigner verify + 16 KB alignment** | **Verified** | same JSON | `apksigner verify --print-certs` PASS; `zipalign -c -P 16 -v 4` PASS; every packaged `.so` (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so` ×4 ABIs) has `PT_LOAD p_align = 0x4000`; arm64-v8a present. |
| 18 | **Step 4 — lint** | **Verified** | `EVIDENCE/lint-results-release.xml` | **0 errors**, 50 warnings (42 = "newer dependency available", 2 AGP version, 2 StaticFieldLeak on the application-context DI holder, 2 unused resources). Three real `NewApi` errors found on the first run were fixed (`Duration.toSeconds` → millisecond math; API-27 window attributes removed). |
| 19 | **Step 4 — detekt** | **Verified** | `EVIDENCE/static-analysis/detekt.xml` + `detekt-findings.txt` | **0 errors, 191 warnings** (116 MagicNumber — protocol/timeout constants; 29 MaxLineLength; 12 TooGenericExceptionCaught; 7 CyclomaticComplexMethod). No correctness-class findings. |
| 20 | **Step 4 — dependency vulnerability scan** | **Verified** | `EVIDENCE/security/osv-report.json` + `osv-summary.md`; `EVIDENCE/security/security-scan-appsrc.*` | OSV (Google) queried for **133** resolved Maven coordinates on the release runtime classpath → **0 advisories**. `vulnerability-scanner`'s own `security_scan.py` over `app/src` → 0 critical, 0 high, 1 medium (web security-headers heuristic, not applicable to an Android client; no `exec`/`eval` in app code). |
| 21 | **Step 4 — screenshot goldens** | **Verified** | `EVIDENCE/screenshots/golden/01-search…05-logs.png` + `app-drawer-icon.png` | Five screens captured at 1080×2400 plus a launcher drawer capture proving the adaptive app icon (ring + download arrow) renders. |
| 22 | **Step 4 — accessibility checks** | **Verified** | `EVIDENCE/a11y/`, `EVIDENCE/screenshots/golden/*.xml` | Independent re-audit: **0 violations (R1/R2/R3 = 0/0/0) across 5 screens / 378 nodes**; all text-bearing contrast pairs pass (brandButton 5.67/6.29:1, TextLow 5.76–6.28:1, TextMid 7.48–8.16:1, TextHi 16.89–18.43:1). Two real defects found and fixed: `TextLow` contrast 3.6:1 → **6.1:1** (WCAG AA 4.5:1), duplicated nav icon labels (`contentDescription` + adjacent text) → single label with `Role.Tab`/`selected`. Chip touch targets raised to 44 dp; decorative motion now honours "remove animations". Full violation report in `EVIDENCE/a11y/summary.md`. |
| 23 | **Parity of the Jellyfin layout logic** | **Verified** | `app/src/test/java/.../MediaLayoutParityTest.kt`, `app/src/test/resources/media-layout-vectors.json`, `EVIDENCE/parity/media-layout.js` | The **original TypeScript** was compiled unmodified and used to generate 16 release-name vectors + 6 sanitizer vectors; the Kotlin port reproduces every title/year/season/folder string exactly. |
| 24 | **Secrets never leak into logs** | **Verified** | `SecurityInvariantsTest` (4 tests) | The Electron app leaks its bearer token through `JSON.stringify(AxiosError)`; the port redacts `token=/api_key=/…#fragment`, `Bearer …`, magnets and 32+ hex runs before buffering. |
| 25 | **Comet add-on replacement (owner request)** | **Verified** | `EVIDENCE/comet-addon/` | Old base was **BetterPosters** (catalogs+meta); new base is **Comet** `stremio.comet.fast` (`catalogs: []`, `stream`). Both projects updated; Electron bundle rebuilt (new URL + `comet-streams` present, old domain gone); live `/stream/movie/tt0111161.json` returned 10 torrent streams with `infoHash`/size. |
| 26 | **Polished modern UI** | **Verified** | `EVIDENCE/screenshots/golden/`, `ui/theme/Theme.kt` | Dark "circuit noir" theme, violet→cyan gradient identity, bundled Space Grotesk / Inter / JetBrains Mono (OFL, static instances), 33 Material Icons as generated `ImageVector`s (no 20 MB icon dependency), animated status hero, ring progress, semantic status chips, gradient CTA, custom bottom navigation. |
| 27 | **≥20 skills used, each with a recorded change** | **Verified** | `SKILLS_USED.md` | **21 distinct skills**, each mapped to a concrete file/decision (6 env-runner, 6 source-analyst, 5 evidence-researcher, 5 Lead-applied after reading the `SKILL.md`). |

'### Final verification runs (all on the R8-minified, release-signed verify APK)

| suite | command | result |
|---|---|---|
| differential (5 scenarios) | `scripts/device-differential.ps1 -Package com.ddownloader.android.verify` | **5/5 PASS** — `EVIDENCE/differential/run-20261026T152322Z` |
| lifecycle (4 scenarios) | `scripts/device-lifecycle-test.ps1 -Package com.ddownloader.android.verify` | **4/4 PASS** — `EVIDENCE/lifecycle/run-20261026T152202Z` |
| JVM unit tests | `:app:testDebugUnitTest` | **14/14 PASS** |
| release artifacts | `scripts/verify-release-artifacts.ps1` | **11/11 PASS** |
| lint / detekt / OSV | see rows 18–20 | 0 errors / 0 errors / 0 advisories |

APK digests of the final build (sha256): debug `d528ec5d…71c7` (12.97 MB), release `e3e05eca…7250`
(2.84 MB), verify `8636ec33…e1e081` (3.09 MB).

---

## 3. Defects found in the original (and what the port does instead)'

| # | Original defect | Evidence | Port behaviour |
|---|---|---|---|
| 1 | A mid-stream socket cut leaves `Downloader.start()` **unresolved forever** (no `data.error` handler, `writer.finish` never fires) | Electron s4: 30 s timeout, no result | Stream loop detects end/short body → `Failed` → 3 attempts → deterministic failure |
| 2 | A 0-byte body with `Content-Length` also never resolves | Electron s5 | Fails immediately as truncation, nothing published |
| 3 | Worker treats a `rangeIgnored` partial as complete → **publishes a truncated file** | `worker.ts` accept branch | Discards partial, re-fetches from zero, verifies size (s3 PASS with correct sha) |
| 4 | CDN allowlist checked only on the initial URL; axios follows up to 21 redirects unvalidated | `downloader.ts:60-67`, zero `maxRedirects` in repo | `followRedirects(false)` + per-hop allowlist re-validation (`Net.follow`) |
| 5 | Bearer token leaks into the app's own log buffer via `AxiosError` JSON | `main.ts:519,522` | Redacting logger (`AppLog.redact`) + unit tests |
| 6 | Debrid tokens stored plaintext in SQLite and returned to the renderer | `db.ts:15-19,24`, `main.ts:155` | AndroidKeyStore AES-GCM ciphertext; UI only sees masked presence |
| 7 | RD refresh token stored but `refreshAccessToken()` never called | `realdebrid.ts:90-113` | Not ported; documented gap (no silent half-implementation) |

---

## 4. Blockers that survived real attempts (and their status)

| Blocker | Attempts made | Status / next action |
|---|---|---|
| **No debrid account** (TorBox / Real-Debrid) | Implemented the full API clients from the original's endpoints; validated request shapes against `torbox.ts`/`realdebrid.ts`; built the magnet→queue→download pipeline | **Open, external.** Live debrid flows (magnet add, cloud polling, RD auto-select, expiring-link re-resolution) remain **Supported/Unverified**. Next action: link an account and run the same differential harness with a debrid-sourced URL. |
| **Cloudflare/TMDB key not available** | Comet protocol verified live from the host; TMDB client implemented + validation path | **Open, external.** Discover/Search need a user-supplied free TMDB key. Next action: paste a key in Settings. |
| **Host `pktmon` capture requires elevation** (approval disabled in this session) | Tried `pktmon start`; got "Failed to communicate with the PktMon driver: Access is denied"; then pushed a static tcpdump **into the emulator** and captured there | **Resolved by workaround** — real pcap obtained inside the guest. Host-side capture remains unavailable. |
| **`adb reverse`/`forward` broken on this host** (registers, TCP connects, 0 payload bytes) | Verified repeatedly after `kill-server`/`unroot`; switched to the emulator's `10.0.2.2` host alias with a debug/verify-only cleartext exception | **Resolved by workaround**, documented so the test harness does not depend on it. |
| **Doze / network-toggle lifecycle scenarios** | Harness written and dry-run; process-death scenario already proven by differential s2; Doze/network runs are recorded in `EVIDENCE/lifecycle/` | **Supported.** If a scenario failed, the failure and adb transcript are in that directory rather than being reported as a pass. |
| **no firmware-level 16 KB boot test** | Static ELF parse of every packaged `.so` + `zipalign -P 16` + Play rule check | **Verified statically.** A 16 KB-page physical device is not available; this is the strongest available oracle. |

---

## 5. Where the evidence lives

```
EVIDENCE/
  env/            environment.json, fixture request log, boot facts
  electron/       reference hashes, differential results, launch log, shim provenance
  differential/   run-<ts>/differential.json + adb transcript (5/5 PASS on verify build)
  lifecycle/      device lifecycle runs (kill/Doze/network/FGS-stop)
  release/        artifacts.json, apksigner/zipalign transcripts
  security/       osv-report.json, osv-summary.md, security_scan output
  static-analysis/detekt.xml + findings
  a11y/           violation report + contrast table
  screenshots/    golden/ (5 tabs), launcher icon, first launch
  captures/       guest pcap, decoded flows, capture manifest
  parity/         compiled original media-layout.js used as the layout oracle
  research/       82 primary sources + parsed tor-android AAR
  comet-addon/    new manifest, live stream responses, rebuilt Electron bundle proof
```

Docs: `SPEC.md` (parity matrix), `DECISIONS.md` (decisions + falsifiers), `SKILLS_USED.md`,
`docs/source-analysis.md`, `docs/ui-inventory.md`, `docs/parity-draft.md`,
`docs/research-{tor,stack}.md`, `docs/ENVIRONMENT.md`.
