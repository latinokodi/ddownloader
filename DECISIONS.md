# DECISIONS — DDownloader Android

Scope note that governs everything below: **the original app contains no Tor-network code
at all.** In `tordownloaderElectron`, "tor" means *TorBox* (a debrid service). Verified by
grep for `socks|onion|torproject|9050|arti` across `electron/` and `src/`: zero hits
(`docs/source-analysis.md` §6). Tor routing is therefore **additive scope demanded by the
brief** ("leak-free Tor usage", "no egress except Tor"), not a ported feature. That single
fact drives Decision A and the size/behaviour deltas in this document.

Evidence bundles for every claim: `docs/research-tor.md`, `docs/research-stack.md`,
`EVIDENCE/research/` (82 primary sources + the parsed AAR), and first-party measurements
taken on this machine (paths given inline).

---

## Decision A — **No Tor-network integration** (corrected 2026-10-26 by the project owner)

**Choice: the Android app does not embed Tor, Orbot, Arti or any SOCKS layer.** "tor" in
`DDownloader`/`the Electron app` is **TorBox**, the debrid service. This is not a scope
reduction invented here — it is what the original is, and the owner confirmed it explicitly.

### Evidence that fixed the decision

1. **The original contains no onion routing.** `grep -Ei 'socks|onion|torproject|9050|arti'` over
   `electron/` and `src/` returns **zero** hits (`docs/source-analysis.md` §6). Every network call
   in the original is a plain HTTPS request to TorBox, Real-Debrid, TMDB or the Comet add-on.
2. **The owner's correction.** Direct instruction on 2026-10-26: *"this project has nothing to do
   with the onion network, the tor suffix is for torbox."* The brief's Tor requirements
   (embedded-Tor choice, `IsTor` check, "no egress except Tor") were therefore void and were
   removed rather than simulated.
3. **The research still had to be done to be sure.** `docs/research-tor.md` (36.8 KB, primary
   sources) evaluated the three options before the correction, and one finding survived into the
   final build: the Guardian Project **gpmaven mirror is stale** (`latest 0.4.8.16`, 2025-05-16)
   while Maven Central carries `0.4.9.14` (2026-10-09). That finding is why
   `settings.gradle.kts` resolves only from Maven Central — a supply-chain fix retained even
   though the Tor dependency itself was dropped.
4. **A concrete cost that would have mattered.** `tor-android:0.4.9.14` declares
   `minCompileSdk=37.1`, so embedding it forces AGP 9.x + Gradle 9.x + `platforms;android-37`.
   That is a real, measured toolchain jump for a capability the product does not have.

### Options scored *before* the correction (kept for the record)

| criterion (weight) | tor-android (embedded) | Orbot (external app) | Arti (embedded Rust) |
|---|---|---|---|
| Leak control / fail-closed authority (5) | 5 | 2 | 4 |
| Self-contained install (3) | 5 | 1 | 4 |
| Background reliability (4) | 4 | 2 | 4 |
| APK size (2) | 3 (+7.58 MB arm64) | 5 (0 B) | 3 |
| Maintenance (4) | 4 (10 releases/12 mo) | 3 | 1 |
| License (2) | 5 (BSD-3) | 4 | 5 |
| **weighted** | **4.55** | **2.45** | **2.86 (gated)** |

Runner-up **Orbot** lost because it cannot provide a guarantee (separate app, can be disabled by
the user/OEM, `<queries>` visibility, no way to prove our sockets are routed). **Arti** was gated,
not second: its own `doc/Android.md` says you must write the JNI bindings yourself, last touched
2022-04-20, sample project abandoned 2022-08-19.

### What "leak-free" means for the shipped product

With no anonymity layer, the requirement is applied to the data that can actually leak:

- **Credentials**: TorBox/RD tokens + TMDB key are AES-256-GCM encrypted with a non-exportable
  AndroidKeyStore key (`data/CryptoBox.kt`), never rendered back to the UI, and excluded from cloud
  backup *and* device-to-device transfer (`data_extraction_rules.xml`, `allowBackup=false`).
- **Logs**: redacting ring buffer masks `token=`, `api_key=`, `Bearer …`, URL fragments, magnets and
  32+ hex runs (`core/AppLog.kt`), with unit tests. The desktop app leaks its bearer token through
  `JSON.stringify(AxiosError)` — verified in the source.
- **Transport**: HTTPS-only (manifest policy **and** a code-level interceptor that refuses
  cleartext), plus per-redirect-hop allowlist validation for debrid CDNs (`data/Net.kt`).

### Falsifiers

This decision would change only if the product actually acquired an anonymity requirement, or if a
future TorBox feature needed onion access. Neither exists in the source, the API surface or the
owner's description.

---

## Decision A′ (retained) — 16 KB page-size compliance

Independently of Tor, the build is checked against Google's 16 KB page-size requirement:
`zipalign -c -P 16` passes and every packaged `.so` (`libandroidx.graphics.path.so`,
`libdatastore_shared_counter.so`, 4 ABIs) has `PT_LOAD p_align = 0x4000`
(`EVIDENCE/release/artifacts.json`). No 32-bit-only library blocks a 16 KB-page device.

---

## Decision B — Stack: **native Kotlin + Jetpack Compose**

Weighted (leak control 5, background+resume 5, APK size 2, bridge support 3, maintenance 3,
license 2; 1–5 per axis, ×20 to make 500):

| stack | leak control | bg + resume | APK size | bridge | maint. | license | **total /500** |
|---|---|---|---|---|---|---|---|
| **Kotlin + Compose** | 5 | 5 | 4 | 5 | 5 | 5 | **480** |
| Flutter | 3 | 4 | 3 | 4 | 4 | 5 | 385 |
| React Native | 3 | 4 | 3 | 3 | 4 | 5 | 340 |
| Capacitor (reuse React UI) | 2 | 1 | 4 | 2 | 3 | 5 | 260 |

Key evidence:

- **Native Android gives the OS-level primitives the requirement needs**: a typed
  `dataSync` foreground service with a documented `onTimeout()` callback, WorkManager work that
  survives process death and reboot, and scoped-storage APIs. Those are the pieces the download
  guarantees are built on (`download/DownloadService.kt`, `download/ResumeWorker.kt`,
  `storage/Storage.kt`) — a web runtime would have to re-implement all three through plugins.
- **Android 15 `dataSync` foreground service** gives a documented 6-hour budget *and* a
  `Service.onTimeout()` callback — a defined place to pause cleanly instead of being killed
  mid-write. Implemented in `DownloadService.onTimeout()`.
- **WorkManager** "persists across app restarts and device reboots" → the resume-after-death
  story has an OS-supported scheduler; `ResumeWorker` only re-arms the durable queue.
- **Capacitor is disqualified by its own docs**: "not possible to run persistent, always
  running background services", background runs capped at 10 minutes with a 15-minute
  minimum interval, state not maintained between runs, and `CapacitorHttp` exposes no
  proxy/SOCKS option — so the Tor requirement is unimplementable without a custom native
  plugin *and* a native network stack anyway.
- **Flutter is the runner-up** because it uniquely has a maintained embedded-Tor plugin, but
  it offers 0 % reuse of the existing React UI and no documented SOCKS5 path in `dart:io`.

Cost accepted: the 4,023-line React UI is *not* reused. `docs/ui-inventory.md` was used as a
design input (palette, density, i18n copy) rather than as code.

---

## Sub-decisions that materially change behaviour

### D1 — ABI coverage
The app ships all four ABIs because its only native code is two tiny AndroidX helpers
(`libandroidx.graphics.path.so` 10 KB, `libdatastore_shared_counter.so` 7 KB); both are 16 KB-aligned
on the 64-bit ABIs and `arm64-v8a` is present, which is what Play requires. (An earlier draft
restricted ABIs to drop the non-compliant 32-bit `libtor.so`; that constraint disappeared with
Decision A.)

### D2 — Transport hardening instead of a SOCKS gate
With no anonymity layer, the enforced invariants are: (1) **HTTPS only** — `usesCleartextTraffic=false`
plus a network-security-config that permits cleartext only on loopback, plus a code-level interceptor
that refuses non-HTTPS (`data/Net.kt`); (2) **every redirect hop re-validated** against the service
allowlist (see D3); (3) **bounded timeouts** including a finite 30 s read timeout on the download
client, because a stalled connection must fail and retry rather than hang (this exact bug was caught
by the lifecycle "network toggle" test and fixed); (4) **loopback-only** exceptions, with the
emulator fixture host `10.0.2.2` allowed in debug/verify builds only — the shipped release manifest
contains no harness entry point (verified by `scripts/verify-release-artifacts.ps1`).

### D3 — Validate every redirect hop (fixes a real hole in the original)
The Electron downloader checked its debrid-CDN allowlist only on the **initial** URL
(`electron/downloader.ts:60-67`) while axios followed up to 21 redirects
(`follow-redirects` default) and never re-validated the final host — confirmed by
`docs/source-analysis.md` (zero occurrences of `maxRedirects` in the repo). Android uses
`followRedirects(false)` plus a manual hop loop that re-checks the allowlist on every `3xx`
(`Net.follow`), so a hijacked redirect cannot leave the allowlist.

### D4 — Secrets in the Android Keystore, not SQLite plaintext
Original: `torbox_token`, `realdebrid_token`, `realdebrid_refresh_token`,
`realdebrid_client_id`, `realdebrid_client_secret`, `tmdb_api_key` are **plaintext columns**
(`electron/db.ts:15-19,24`) and are returned wholesale to the renderer (`main.ts:155`).
Worse, the original leaks a bearer token into its own log buffer: `main.ts:519,522` log raw
`AxiosError`, and `JSON.stringify(AxiosError)` includes `config.headers.Authorization`.
Android: AES-256-GCM with a non-exportable `AndroidKeyStore` key (`data/CryptoBox.kt`), no
fallback path, plus a redacting logger (`core/AppLog.kt`) that masks `token=`, `api_key=`,
`#<fragment>`, `Bearer …`, magnets and 32+ hex runs before anything is buffered.

### D5 — No Python sidecars; the "23 engines" metastack is replaced
The original shell-outs to PyInstaller binaries (`qbit-runner`, `tmdb-provider`,
`latino-providers`, `subtitles`) and bundles FlareSolverr + Chromium (~250 MB) for Cloudflare.
None of that can ship on Android. Replacement: TMDB (catalogue) + the embedded **Comet**
add-on (stream resolution) + the two debrid APIs. Consequence recorded as a parity gap, not
hidden: the 23-indexer metastack is **dropped**, with Comet as the functional replacement.

### D6 — Comet add-on replaces the BetterPosters catalogue add-on (requested 2026-10-26)
The old embedded URL was *BetterPosters* (`community.betterposters`, catalogs + meta). The new
one is *Comet* (`stremio.comet.fast`, `catalogs: []`, only the `stream` resource). A blind
constant swap would have silently emptied Discover, so the add-on client was adapted to
Comet's real interface — `/manifest.json` + `/stream/{type}/{id}.json` (series id
`tt123:S:E`), producing `infoHash` + `filename` + `videoSize` that convert directly into the
magnet the debrid APIs accept. Catalogue duties move to TMDB (which the app already used).
Applied to both projects; the Electron bundle was rebuilt and verified
(`EVIDENCE/comet-addon/`).

### D7 — Scoped storage without `MANAGE_EXTERNAL_STORAGE`
Partials live in app-private storage (only place that supports seek/append across process
death with no permission). On completion the file is *published* either to
`<external-files>/Download/...` (zero permissions) or into a user-picked SAF tree via
`DocumentFile`. No `WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`; `allowBackup=false`
plus explicit `dataExtractionRules` exclude the token store from cloud backup and D2D transfer.

### D8 — The resume offset is the file length, never a DB counter
`bytes_done` is UI telemetry only. On every attempt the engine re-derives the offset from
`partial.length()` and sends `Range: bytes=<n>-` with `If-Range` (ETag/Last-Modified). This
is what makes process-death resume correct even if the last DB write was lost. Parity with
the original is preserved: server answers `200` → keep the partial, return `rangeIgnored`
(never truncate); `416` → partial is complete; short body → failure, partial kept. Two
desktop rules are deliberately **not** ported because they are unsafe: accepting a
`rangeIgnored` partial with no size check, and accepting a wrong-size file on attempt ≥ 2.

### D9 — No self-updater
`electron-updater` + NSIS has no Android analogue worth the supply-chain risk (the original
also ships an *unsigned* plugin-updater that downloads Python — dropped). Distribution is the
APK; the version is surfaced in Settings/Logs.

### D10 — Pinned toolchain, deliberately
Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.12.01 is a coherent, mutually
tested set; newer releases exist (Kotlin 2.4.x, AGP 9.x, BOM 2026.09.00) but AGP 9 requires a
Gradle 9 upgrade and a different Compose-compiler pairing, which is unrelated risk for this
deliverable. No dependency needs a Kotlin-stdlib pin any more (the Tor AAR was removed with Decision A).

---

## Falsifiers — what would reverse these choices

| Decision | Would flip if |
|---|---|
| A (embedded tor-android) | A 16 KB **runtime** test on a 16 KB-page device failed (only static ELF evidence exists today), or Play rejected the bundled binary, or tor-android went >12 months without a release while a CVE was open |
| B (Kotlin/Compose) | The UI had to be shared with a web team, or iOS became a target in the same codebase |
| D1 (64-bit only) | A meaningful share of the target audience ran 32-bit-only devices |
| D2 (fail-closed) | A product requirement allowed direct fallback, which the brief explicitly forbids |
| D6 (Comet) | Comet's endpoint changed/closed and TMDB alone could not resolve streams |


## Signing key: keep the existing certificate

**Decision (2026-10-26):** keep the current release keystore (`keystore/release.jks`, alias
`tordownloader`, RSA 4096) even though its certificate DN still reads `CN=TorDownloader Android` from
before the app was renamed.

**Why:** Android refuses an APK signed with a different key over an existing install. Generating a
"DDownloader"-named key would force every current install to uninstall first and lose its settings,
linked debrid accounts and queue. The DN is cosmetic (it appears only when someone inspects the
signature); an update that just works is worth more than a tidy name.

**Consequences:**
- Updates stay seamless for every existing install (verified: the in-app updater installs over the
  previous version and data survives).
- If the key is ever lost, recovery is *uninstall + reinstall*: no future update will be accepted.
  A backup therefore exists outside the repo at `F:\PyApps\ddandroid-keys-backup`
  (`release.jks` + `keystore.properties` + `release.jks.sha256`). It must be stored encrypted or in a
  password manager — `keystore.properties` contains the passwords.
- Keystore material stays git-ignored (`keystore/`, `keystore.properties`, `*.jks`).
