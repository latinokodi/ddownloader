# Decision B — Stack: Kotlin+Compose vs Capacitor vs Flutter vs React Native

**Status:** evidence complete, decision recommended · **Research date:** 2026-10-26 (America/Bogota) · **Author:** evidence-researcher
**Raw evidence:** `EVIDENCE/research/raw/` (registry metadata, Android docs, repo APIs), fetched during this session.
**Method:** versions/dates come from registry APIs (npm/pack.dev/crates.io/Maven Central) and official docs, quoted with URL + fetch date. Where a number could not be verified (APK sizes — no Android/Flutter/Gradle toolchain on this machine) it is marked `UNVERIFIED` and excluded from the scores.

---

## Executive answer

1. **Recommend native Kotlin + Jetpack Compose.** It is the only option where the two highest-weighted requirements — **fail-closed routing** and **download resume across process death** — are fully under your control with first-party, documented APIs: OkHttp passes SOCKS hostnames unresolved (remote DNS), WorkManager persists work "across app restarts and device reboots", and Android 15's `dataSync` 6-hour limit has an explicit `onTimeout()` contract you can handle (B4.2–B4.5).
2. **The stack is not the main cost — the Electron main process and its vendored binaries are.** `electron/` contains `flaresolverr` (with a bundled Chrome, ~600 MB of `.dll`s), `qbit-runner.exe` (14.7 MB), and 5 Python sidecars. None of those can ship in an APK, so **every** option pays a large native re-implementation cost. What differs is the UI (only 4,023 lines) and the bridge work.
3. **Runner-up: Flutter**, because it is the only cross-platform option with a *maintained plugin for every bridge you need*: `background_downloader 9.6.4` (2026-10-07), `flutter_foreground_task 11.0.3` (2026-09-07), and — uniquely — `tor 0.2.2` (2026-10-21, Arti-based, MIT), the only maintained embedded-Tor package in any of the three cross-platform ecosystems (B3.4/B5.3). It loses on leak control (Dart's HTTP stack has no documented SOCKS5 support — you need a plugin) and on UI reuse (0% of the React UI carries over).
4. **Capacitor ranks last for this app**: its own docs say *"It's not possible to run persistent, always running background services on mobile operating systems"* and cap Android background tasks at **10 minutes per run / 15-minute minimum interval**, with *"State is not maintained between calls"* (B2.2); `CapacitorHttp` documents **no proxy/SOCKS option** (B2.3); and no Capacitor Tor plugin exists (B2.4). Its one advantage — reusing the React UI nearly verbatim — does not offset reimplementing all 47 IPC methods plus a native download engine anyway.
5. **React Native is the weakest fit**: no maintained Tor bridge (`react-native-tor` last published **2022-02-09**, repo last pushed 2022-02-12, 22 open issues) and the canonical `react-native-background-downloader` is abandoned (`2.3.4`, **2019-12-10**), served only by a community fork (B5.2).

---

## B0. Verified versions as of 2026-10-26

| Item | Version | Published / lastUpdated | License | Source (fetched 2026-10-26) |
|---|---|---|---|---|
| Android SDK targets | API 36 = Android 16 stable | — | — | `capacitorjs.com/docs/getting-started/environment-setup` ("The latest stable version is Android 16 (API 36)") |
| Kotlin stdlib (stable) | **2.4.21** | Maven Central metadata lastUpdated 2026-10-21 | **Apache-2.0** (POM `<name>Apache-2.0</name>`) | `repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml` + `…/2.4.21/kotlin-stdlib-2.4.21.pom` |
| Android Gradle Plugin (stable) | **9.4.1** | metadata 2026-10-22 | Apache-2.0 (Google) | `dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml` |
| Compose BOM | **2026.09.00** | 2026-09-09 | Apache-2.0 (AndroidX) | `…/androidx/compose/compose-bom/maven-metadata.xml` |
| WorkManager | **2.12.0** | 2026-09-23 | Apache-2.0 | `…/androidx/work/work-runtime-ktx/maven-metadata.xml` |
| Room | **2.8.5** | 2026-09-09 | Apache-2.0 | `…/androidx/room/room-runtime/maven-metadata.xml` |
| OkHttp | **5.5.1** | 2026-10-22 | Apache-2.0 | `repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml` |
| Capacitor core/android | **8.5.3** | 2026-10-07 | **MIT** | npm registry `@capacitor/core`, `@capacitor/android` |
| @capacitor/background-runner | **3.0.0** | 2025-12-10 | MIT | npm registry |
| Flutter (stable) | **3.47.8** (Dart 3.13.7) | **2026-10-23** | **BSD-3-Clause** | `storage.googleapis.com/flutter_infra_release/releases/releases_windows.json`; `raw.githubusercontent.com/flutter/flutter/master/LICENSE` (3-clause text) |
| `background_downloader` (pub) | **9.6.4** | 2026-10-07 | (see repo) | `pub.dev/api/packages/background_downloader`; repo: 241★, **19 open issues**, pushed 2026-10-23 |
| `flutter_foreground_task` (pub) | **11.0.3** | 2026-09-07 | — | pub.dev API |
| `flutter_downloader` (pub) | **1.12.1** | 2026-08-22 | — | pub.dev API; repo **351 open issues** |
| `tor` (pub, Arti-based) | **0.2.2** | **2026-10-21** | **MIT** | `pub.dev/api/packages/tor`; `api.github.com/repos/Foundation-Devices/tor` (24★, **12 open issues**, pushed 2026-10-25) |
| React Native | **0.88.0** | **2026-10-15** | **MIT** | npm registry `react-native` |
| @kesha-antonov/react-native-background-downloader | **4.6.4** | 2026-10-21 | Apache-2.0 | npm; repo 222★, **0 open issues**, pushed 2026-10-21 |
| react-native-background-downloader (original) | **2.3.4** | **2019-12-10** | Apache-2.0 | npm registry (abandoned) |
| react-native-tor | **0.1.8** | **2022-02-09** | MIT | npm; repo pushed **2022-02-12**, 22 open issues (abandoned) |
| react-native-foreground-service | **1.0.0** | **2020-11-06** | MIT | npm registry (abandoned) |

**B0.1 — Version sanity check.** Kotlin's Maven `<release>` currently points at `2.5.0-Beta2` (published 2026-10-21); **latest stable is 2.4.21** — do not let a naive "latest release" parser pin a beta (this is why B0 lists 2.4.21). Same trap exists on AGP (`<release>9.6.0-alpha01</release>`, stable **9.4.1**).

---

## B1. Criterion 1 — Leak control / fail-closed network routing

**B1.1 — Native (Kotlin) is the strongest.** OkHttp, fetched 2026-10-26 (`square/okhttp/…/RouteSelector.kt`):
```kotlin
if (proxy.type() == Proxy.Type.SOCKS) {
  routes += Route(proxy, InetSocketAddress.createUnresolved(socketHost, socketPort), …)
```
⇒ hostnames are handed to the proxy **unresolved** (remote DNS ⇒ no local resolver leak), and a `ProxySelector`/`Route` set that yields nothing fails the call instead of silently going direct. Combined with owning every socket (no `DownloadManager`, no WebView unless proxied), native gives a provable "no direct egress" property.
**B1.2 — Flutter is mid.** `dart:io` `HttpClient` is the default stack for `package:http`/`dio`; **I could not verify any documented SOCKS5 support in `dart:io`** (`UNVERIFIED` — no primary doc fetched). Practically you need a plugin or Rust/FFI transport to route through Tor SOCKS5 with remote DNS. Score reflects: control is possible, but not with the stock HTTP client.
**B1.3 — React Native is mid.** RN's networking on Android is OkHttp under the hood, but there is **no supported JS-visible proxy configuration**; you must write a native module or patch the OkHttp client. `react-native-tor` (the only embedded-Tor package) is abandoned since 2022 (`0.1.8`, 2022-02-09; repo pushed 2022-02-12), so Tor must be custom-linked.
**B1.4 — Capacitor is weakest.** `CapacitorHttp` patches `window.fetch`/`XMLHttpRequest` to native libraries, but its documented `HttpOptions` — `url, method, params, data, headers, readTimeout, connectTimeout, disableRedirects, webFetchExtra, responseType, shouldEncodeUrlParams, dataType` — contains **no proxy, SOCKS, or route option** (fetched 2026-10-26). A WebView-originated request cannot be constrained to Tor without a custom native plugin, and any page/asset load via the WebView escapes your routing unless the whole WebView is proxied at the network layer.

---

## B2. Capacitor — detail

**B2.1 — Version/support.** `@capacitor/core` + `@capacitor/android` **8.5.3 (2026-10-07)**, MIT. Env: **Node 22+**, **Android Studio ≥ 2025.2.1**, SDK platform **API 24+** (fetched `capacitorjs.com/docs/getting-started/environment-setup`).
**B2.2 — Background execution is explicitly constrained** (`capacitorjs.com/docs/apis/background-runner`, fetched 2026-10-26):
- *"Currently, the runners are designed for performing periodic bursts of work… As a result, runners are **not long lived**. State is not maintained between calls to events in the runner."*
- Android: *"Your task has a **maximum of 10 minutes** to perform work"*, *"Repeating background tasks have a **minimal interval of at least 15 minutes**."*
- *"It's **not possible to run persistent, always running** background services on mobile operating systems."*
⇒ No long-lived download service via JS. `@capacitor/background-runner` **3.0.0** (2025-12-10) is a periodic-task primitive, not a downloader.
**B2.3 — HTTP/proxy gap.** See B1.4. Also documented: *"Due to the nature of the bridge, parsing and transferring large amount of data from native to the web can cause issues. Support for downloading and uploading files has been added to `@capacitor/file-transfer`"* — i.e. large downloads are pushed to a separate plugin.
**B2.4 — No Tor plugin exists.** npm search `capacitor tor` (fetched 2026-10-26) returned only `tor-proxy-agent` (Node-only, useless on Android) and unrelated Capacitor plugins. `@capacitor-community/http` is **1.4.1 from 2021-10-11** (abandoned).
**B2.5 — React UI reuse: the one real advantage.** The renderer is React 18 + Zustand + Tailwind (`package.json`: `react ^18.3.1`, `zustand ^5.0.13`, `tailwindcss ^3.4.16`), 20 `.tsx` + 11 `.ts` = 4,023 lines — these components would run in a Capacitor WebView largely unchanged. But the app talks to the shell through **`window.electronAPI` in 9 files**, backed by a typed contract (`src/types/electron.ts`) and **47 `ipcMain.handle`** endpoints; all 47 need a Capacitor plugin implementation. And the heavy lifting (download engine, debrid clients, DB, Python sidecars) is native/node-side anyway.
**B2.6 — WebView dependence.** The UI runs in the platform WebView (architectural; `CapacitorHttp` exists precisely to patch `window.fetch`/`XHR` for it). That means: WebView version fragmentation, no guaranteed 16 KB-aligned native contribution from the shell itself, and the WebView's own network stack must be proxied separately for Tor. **B2.7 — License:** MIT (npm `license` field).

---

## B3. Flutter — detail

**B3.1 — Version/date.** Stable **3.47.8 / Dart 3.13.7, released 2026-10-23** (`releases_windows.json` `current_release.stable`). **License BSD-3-Clause** (`raw.githubusercontent.com/flutter/flutter/master/LICENSE`: "Copyright 2014 The Flutter Authors… Redistribution and use in source and binary forms…" + 3 clauses) — verified by fetch.
**B3.2 — Background downloads/foreground service plugins exist and are maintained.** `background_downloader` **9.6.4 (2026-10-07)**, repo 241★, **19 open issues**, pushed 2026-10-23; `flutter_foreground_task` **11.0.3 (2026-09-07)**.
**B3.3 — Negative: the older popular downloader is unhealthy.** `flutter_downloader` **1.12.1 (2026-08-22)** but repo has **351 open issues**, including still-open Android 14 failures: **#982 "Downloading doesn't work for Android 14"** (opened 2024-12-13) and **#959 "flutter downloader is not downloading in Android versions of 13 and 14"** (opened 2024-05-29). ⇒ Pick `background_downloader`, not `flutter_downloader`.
**B3.4 — Unique: a maintained embedded-Tor package.** `tor 0.2.2` (pub, published **2026-10-21**), described as *"A multi-platform Flutter plugin for managing a Tor proxy. Based on arti."* — FFI plugin (`flutter_rust_bridge 2.11.1`, `cargokit`), platforms android/ios/linux/macos/windows, MIT, repo `Foundation-Devices/tor` (24★, 12 open issues, pushed 2026-10-25). It is **pre-1.0** (0.2.2) and only 24 stars — promising, not proven.
**B3.5 — Background isolate restrictions:** Flutter isolates are process-bound; long work must be delegated to a native FGS plugin (B3.2) — same platform limits as anyone else, but the JS-style "keep the runtime alive" trick is not available.

---

## B4. Kotlin + Jetpack Compose — detail (official APIs)

**B4.1 — Compose/versions:** Kotlin **2.4.21** (Apache-2.0, POM-verified), AGP **9.4.1**, Compose BOM **2026.09.00**, WorkManager **2.12.0**, Room **2.8.5**, OkHttp **5.5.1** (B0).
**B4.2 — Background downloads that survive process death.** `developer.android.com/develop/background-work/background-tasks/persistent` (fetched 2026-10-26): *"WorkManager features a robust scheduling mechanism that lets tasks **persist across app restarts and device reboots**"*; *"Scheduled work is stored in an internally managed SQLite database and WorkManager takes care of ensuring that this work persists and is rescheduled across device reboots."*
**B4.3 — Long-running work uses a foreground service from the worker.** Same doc: type *"Long Running: Tasks which might run for longer, potentially longer than 10 minutes"*, accessed via *"Any `WorkRequest` or `Worker`. Call `setForeground()` in the Worker to handle the notification."*
**B4.4 — Android 15 6-hour `dataSync` limit + `onTimeout()` (verbatim).** `developer.android.com/about/versions/15/behavior-changes-15` (fetched 2026-10-26): *"The system permits an app's `dataSync` services to run for a total of **6 hours in a 24-hour period**, after which the system calls the running service's `Service.onTimeout(int, int)` method (introduced in Android 15). At this time, the service has a few seconds to call `Service.stopSelf()`. When `Service.onTimeout()` is called, the service is no longer considered a foreground service. If the service does not call `Service.stopSelf()`, the system throws an internal exception."* Mitigations listed include implementing `onTimeout()` and starting `dataSync` only from **direct user interaction** (resets the 24 h budget). ⇒ Design: chunked, resumable downloads (HTTP `Range` + Room checkpoint), explicit `onTimeout` → persist + stop, user tap to resume.
**B4.5 — 16 KB obligation is app-side and satisfiable:** AGP ≥ 8.5.1, uncompressed `.so`, `zipalign -c -P 16`; requirement date **2027-02-01** (quoted in `research-tor.md` A4.1). AGP 9.4.1 ≫ 8.5.1.
**B4.6 — MediaStore/SAF and FGS types** are first-party (`FOREGROUND_SERVICE_DATA_SYNC`, `MediaStore`/`ACTION_CREATE_DOCUMENT`) — no bridge tax.
**B4.7 — Real-world Android app evidence for long background work:** Orbot itself — manifest declares a `systemExempted` foreground service + `FOREGROUND_SERVICE_DATA_SYNC` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + boot receiver + a separate `:tor` process (`orbot-AndroidManifest.xml`, fetched 2026-10-26). That is what a shipping app actually needs to survive; it is all native API surface, none of it reachable from a WebView/JS shell without a plugin.
**B4.8 — Cost:** UI is a full rewrite (B6); no shared code with the Electron renderer.

---

## B5. React Native — detail

**B5.1 — Version.** `react-native` **0.88.0 (2026-10-15)**, MIT (npm `license`). Fast cadence ⇒ upgrade tax.
**B5.2 — Background downloads: fork-only.** Canonical `react-native-background-downloader` is **2.3.4 (2019-12-10)** — abandoned. Maintained community fork `@kesha-antonov/react-native-background-downloader` **4.6.4 (2026-10-21)**, repo 222★, **0 open issues**, pushed 2026-10-21. Foreground-service helper `react-native-foreground-service` is **1.0.0 (2020-11-06)** — abandoned.
**B5.3 — No maintained Tor bridge.** `react-native-tor` **0.1.8** published **2022-02-09**, repo pushed **2022-02-12**, 22 open issues. npm search for Tor (fetched 2026-10-26) shows only Node-side proxy agents (`socks-proxy-agent`, `tor-proxy-agent`) which do nothing inside an APK. ⇒ You would hand-write a native module wrapping `tor-android` (i.e. do Decision A's option 1 twice: once per platform).
**B5.4 — Hermes / new architecture:** 0.88 ships the new architecture as the norm; **`UNVERIFIED`** for the exact 0.88 release notes (not fetched). Treat any "just works" assumption as unverified.
**B5.5 — License MIT.**

---

## B6. Porting the existing React UI — measured effort

| Fact (verified) | Value | Consequence by stack |
|---|---|---|
| React UI size | **4,023 lines** (20 `.tsx`, 11 `.ts`) | Small enough that a rewrite is weeks, not months |
| State | Zustand stores in `src/store/discoverTabs.ts`, `src/store/searchTabs.ts` | Trivial to mirror (ViewModel/StateFlow, Riverpod, Redux) |
| Shell contract | `window.electronAPI` in **9 files**, typed in `src/types/electron.ts`, **47 `ipcMain.handle`** in `electron/main.ts` | Capacitor: 47 methods → 1 custom plugin; RN/Flutter/Kotlin: reimplement the domain layer directly |
| Styling | Tailwind 3.4 | Capacitor/WebView: reused; RN: needs NativeWind port; Compose/Flutter: redesign |
| Native logic to replace | `electron/main.ts` 45,339 B, `electron/worker.ts` 38,313 B, `downloader.ts`, `db.ts` (better-sqlite3), `torbox.ts`, `realdebrid.ts`, `metasearch.ts`, `cf-fetcher.ts`, plus Python sidecars (`latino-providers.py` 18,343 B, `subtitles.py`, `tmdb-provider.py`) and vendored binaries (`flaresolverr` with Chrome, `qbit-runner.exe` 14.7 MB) | **Stack-independent, dominant cost.** None of the vendored binaries can ship in an APK; Cloudflare-solving/FlareSolverr must be re-hosted or replaced |

⇒ UI reuse favors **Capacitor** strongly; **everything else** favors native. Since the native re-implementation dominates the schedule (B6 last row), optimizing UI reuse is a second-order saving that costs you the two highest-weighted criteria.

---

## B7. Weighted scoring

Weights (defensible for a debrid downloader that is judged on "does it download reliably and never leak"): **leak control 25**, **background + resume across process death 25**, bridge support (Tor/FGS/SAF) 15, maintenance 15, APK size 10, license 10.

| Criterion (weight) | Kotlin+Compose | Flutter | React Native | Capacitor |
|---|---|---|---|---|
| Leak control / fail-closed (25) | **5** — OkHttp SOCKS passes hostnames unresolved (remote DNS), you own every socket (B1.1) | **3** — no documented SOCKS5 in `dart:io`; needs plugin/FFI (B1.2) | **4** — OkHttp under the hood but no supported proxy config; needs a native module (B1.3) | **2** — `CapacitorHttp` has no proxy option; WebView escapes routing (B1.4) |
| Background + resume (25) | **5** — WorkManager persists across reboot + FGS `setForeground` + `onTimeout` contract (B4.2–B4.4) | **4** — `background_downloader 9.6.4` + `flutter_foreground_task 11.0.3` maintained; older `flutter_downloader` still open-broken on Android 14 (B3.2/B3.3) | **3** — maintained fork only; canonical package abandoned 2019 (B5.2) | **2** — docs: not long-lived, 10-min runs, 15-min minimum, no persistent service (B2.2) |
| Bridge support Tor/FGS/SAF (15) | **5** — `tor-android` AAR + native FGS + MediaStore/SAF, all first-party (B4, research-tor A1) | **4** — `tor 0.2.2` (Arti) + FGS + downloader plugins; most complete cross-platform set (B3.4) | **2** — no maintained Tor package; FGS helper abandoned since 2020 (B5.2/B5.3) | **2** — no Tor plugin; FGS/SAF need custom plugins (B2.4) |
| Maintenance / cadence (15) | **5** — official platform; AGP 9.4.1 / Kotlin 2.4.21 / BOM 2026.09.00 all current (B0) | **4** — 3.47.8 on 2026-10-23, healthy plugins (B3.1/B3.2) | **3** — 0.88.0 on 2026-10-15 but fast breaking cadence + abandoned key plugins (B5) | **4** — 8.5.3 on 2026-10-07, MIT, steady (B2.1) |
| APK size (10) | **3** — Compose + no runtime bundled; baseline `UNVERIFIED`; Tor adds 7.58 MB (arm64) (research-tor A1.5) | **4** — engine per-ABI, typical hello-world is single-digit MB but `UNVERIFIED` here | **4** — Hermes/engine per-ABI `UNVERIFIED` | **2** — WebView shell + bridge, plus system WebView dependency (B2.6) |
| License (10) | **5** — Apache-2.0 (Kotlin POM verified) | **5** — BSD-3 (LICENSE fetched) | **5** — MIT | **5** — MIT |
| **Weighted total /500** | **480 (96%)** | **385 (77%)** | **340 (68%)** | **260 (52%)** |

---

## B8. Recommendation

**Build native Kotlin + Jetpack Compose** (module layout: `:core:tor`, `:core:download`, `:data`, `:ui`), with:
- `info.guardianproject:tor-android:0.4.9.14` in-process (Decision A) wrapped in a `dataSync` foreground service, one dedicated `:tor` process;
- a **WorkManager `OneTimeWorkRequest` chain** as the durable download engine + Room checkpoint per chunk (HTTP `Range` resume), `Worker.setForeground()` for the visible phase, and `Service.onTimeout()` persisted-and-stop handling for the 6 h/24 h cap;
- OkHttp with a hard `Proxy.Type.SOCKS` route and **no DIRECT fallback**, plus an egress unit test that fails the build if any client is constructed without the SOCKS proxy;
- the 47-method `ElectronAPI` contract from `src/types/electron.ts` re-expressed as Kotlin interfaces, so the port is a mechanical checklist.

**Runner-up: Flutter.** It is second because it is the only option that covers *all* bridges with maintained packages — including the only maintained embedded-Tor plugin in any cross-platform ecosystem (`tor 0.2.2`, Arti). Evidence that makes it lose: (i) leak control depends on non-default transports (B1.2); (ii) 0 % of the React UI carries over; (iii) the Tor plugin is pre-1.0 with 12 open issues and 24 stars (B3.4) — you would be the early adopter.

### What would change the answer
- **If the React UI + web skills must be reused and Tor is dropped** → **Capacitor** becomes defensible (UI reuse is near-verbatim; but accept 10-min/15-min background constraints and build a native download plugin).
- **If iOS is a hard requirement** → **Flutter**, with `tor 0.2.2` + `background_downloader`; Kotlin+Compose has no iOS story for `tor-android` (iOS would need Arti/Orbot there anyway).
- **If a maintained `tor-android`-style AAR (or official Arti bindings) appears for iOS** → the cross-platform argument weakens further and native stays the answer.
- **If APK size dominates** (e.g. low-end-device market): ABI splits + arm64-only + `isShrinkResources`; note the Tor AAR alone is 7.58 MB arm64 — a no-Tor build is dramatically smaller.

---

## B9. UNVERIFIED / gaps

1. **APK sizes for hello-world releases — all four stacks `UNVERIFIED`.** No toolchain on this machine (`flutter`, `gradle`, `adb` absent; `node 24.19.0` and JDK 21 present but no Android SDK/platform tools). The only measured size is the Tor AAR: **12,976,254 B all-ABI / 7,580,256 B arm64** (`research-tor.md` A1.5). Do **not** quote hello-world numbers from secondary blogs.
2. **"Real app" evidence for background download + resume on Android 14+** is partial: primary/official (WorkManager persists across reboot; Android 15 `onTimeout`), artifact-level (Orbot's manifest), and issue-level (`flutter_downloader` #982/#959 still open for Android 14). No RN-specific Android 14 bug was verified, and no on-device run was performed for any stack.
3. **Dart `dart:io` SOCKS5 support** not verified from official docs — B1.2 is scored conservatively on that basis.
4. **RN 0.88 release notes / Hermes specifics** not fetched (`UNVERIFIED`).
5. **Compose/AndroidX license** inferred from the AndroidX family (Apache-2.0); I verified Kotlin's POM license but the `compose-bom` POM license block did not match my grep pattern, so Compose licensing is "standard Apache-2.0, not independently quoted".
6. **Flutter hello-world size and per-ABI engine size** not measured.
7. `react-native-tor`'s abandonment is established from registry + repo timestamps; whether a private/other maintained Tor bridge exists outside npm search results is `UNVERIFIED` (absence-of-hits).

---

## B10. Skills applied (this document)

| Skill | Path | What it changed (precise) |
|---|---|---|
| `security-and-hardening` | `F:\PyApps\agent-skills\.agent\skills\security-and-hardening\SKILL.md` | Applied "map the trust boundary / fail closed / never trust the client" to the stack matrix: created **B1** as a first-class criterion with the OkHttp `createUnresolved` evidence, and added the build-time egress test to the recommendation in **B8** ("fails the build if any client is constructed without the SOCKS proxy") — that requirement does not exist in the Electron app today. |
| `supply-chain-security` | `…\skills\supply-chain-security\SKILL.md` | Its "new dependency checklist (maintenance, last 6 months, dependency tree, license)" produced the **published-date/open-issue table in B0** and the explicit rejections: `react-native-background-downloader` (2019), `react-native-foreground-service` (2020), `react-native-tor` (2022), `@capacitor-community/http` (2021). It also forced the fork-vs-canonical distinction in B5.2 rather than "RN has a downloader". |
| `deep-research` | `…\skills\deep-research\SKILL.md` | Primary-source discipline + explicit gap reporting: every version in B0 has a URL and fetch date, and **B9** states that no APK size was measured rather than citing blog numbers. It also drove the negative hunt that found `flutter_downloader` #982/#959 and the `<release>`-points-at-beta trap (B0.1). |
| `privacy-by-design` | `…\skills\privacy-by-design\SKILL.md` | Drove the decision to keep Tor **opt-in but always fail-closed** (B8) and to forbid logging of magnet links/debrid API keys (carried from `research-tor.md` A9), which in turn raised the weight of leak control to 25. |
| `architecture-decision-records` | `…\skills\architecture-decision-records\SKILL.md` | Imposed the "decision drivers → weighted options → decision → consequences → what would change it" shape (B7/B8), including the explicit runner-up and falsifiers instead of a bare ranking. |
