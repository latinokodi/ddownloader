# Decision A — Tor integration on Android (research evidence)

**Status:** evidence complete, decision recommended · **Research date:** 2026-10-26 (America/Bogota) · **Author:** evidence-researcher
**Raw evidence:** `EVIDENCE/research/raw/` (82 fetched files), `EVIDENCE/research/artifacts/` (the AAR + parse outputs), `EVIDENCE/research/scripts/` (parser scripts, reproducible).
**Method:** every load-bearing claim below is from a primary source fetched during this session (Maven Central metadata, GitHub/GitLab APIs, upstream README/CHANGELOG/source, torproject.org forum, developer.android.com). Artifact-level claims were verified **empirically** by downloading and parsing the shipped binary. Claims I could not verify are listed in §10 and marked `UNVERIFIED`.

---

## Executive answer

1. **If Tor is required, embed Guardian Project `tor-android` (option 1).** Latest is `info.guardianproject:tor-android:0.4.9.14`, on **Maven Central** (not only the Guardian Project mirror), published **2026-10-09** — *one day after* Tor 0.4.9.14, which upstream describes as a **high-severity security release** (A1.1–A1.4). It is BSD-3-Clause, and it is the same native stack Orbot itself ships (A2.1, A7.1).
2. **16 KB page size: PASS on both 64-bit ABIs.** Empirically parsed from the shipped `libtor.so`: `arm64-v8a` and `x86_64` each have 3 `PT_LOAD` segments, **all with `p_align = 0x4000` (16 KiB)**, and `GNU_RELRO` satisfies Google's documented exception, so no RELRO SIGSEGV is expected (A4.2–A4.5). `armeabi-v7a`/`x86` remain 4 KB ELF32, which is **out of scope** of the Play rule (it targets 64-bit devices).
3. **Runner-up: Orbot (option 3)** — zero APK cost and the lowest maintenance risk, but it is a hard dependency on a second app the user can uninstall, disable, or configure to refuse background starts (`STATUS_STARTS_DISABLED`), so the app cannot *guarantee* fail-closed Tor (A7.2–A7.4, A8.2).
4. **Arti (option 2) is GATED today, not merely "harder".** Arti's own Android guide says *"Arti does not have a stable Rust API yet. For that reason, no proper bindings are provided. You'll need to write these bindings yourself using the Java Native Interface"* — and that guide was last modified **2022-04-20**; the sample Android project's last activity is **2022-08-19** (A6.1–A6.3). Do not plan a Kotlin app around it in this milestone.
5. **Scope warning for the Lead (decisive context):** the existing Electron app **does not use the Tor network at all** — it is a *TorBox* debrid client. Zero SOCKS/proxy/9050 references exist in `src/` + `electron/` (A0.1). Tor integration is therefore **new functionality**, not parity: it costs +12.98 MB (all ABIs) / +7.58 MB (arm64 only) in the APK, adds a second foreground-service lifecycle, and adds a leak surface. Decide *whether* Tor is required before committing to option 1.

---

## A0. Context check — is there Tor today?

**A0.1 — The Electron app contains no Tor-network code.** `Select-String -Pattern 'socks|9050|tor\.project|ProxyAgent|tunnel'` over `F:\PyApps\tordownloaderElectron\src` + `electron` (excluding vendored `flaresolverr/`, `node_modules/`) returned **0 matches**. The only "tor" tokens are `https://tor.box/link` (TorBox OAuth) in `src/components/SettingsModal.tsx:326-327`. Verified 2026-10-26 by grep (command in session log).
**Consequence:** option 1/2/3 add capability that the product has never shipped. Any claim that the port needs Tor "for parity" is false.

**A0.2 — Porting surface (for effort framing).** `src/` = 20 `.tsx` + 11 `.ts` = **4,023 lines**; renderer talks to main only through a typed `window.electronAPI` contract (`src/types/electron.ts`, `const electronAPI = (window as any).electronAPI` in 9 files); `electron/preload.ts` uses `contextBridge.exposeInMainWorld`; `electron/main.ts` registers **47 `ipcMain.handle`** handlers (45,339 bytes); `electron/worker.ts` is 38,313 bytes. The API contract being typed is an asset: any native port can mirror it 1:1.

---

## A1. Availability & packaging

**A1.1 — Maven Central hosts it; latest = 0.4.9.14.** `https://repo1.maven.org/maven2/info/guardianproject/tor-android/maven-metadata.xml` (fetched 2026-10-26) → `<release>0.4.9.14</release>`, `<lastUpdated>20261009190835</lastUpdated>` → **2026-10-09 19:08:35 UTC**. The directory listing `https://repo1.maven.org/maven2/info/guardianproject/tor-android/` returns HTTP 200 with **37 version directories**, newest `0.4.9.14/` dated `2026-10-09 19:03`.

**A1.2 — README-recommended coordinates (verbatim).** `https://raw.githubusercontent.com/guardianproject/tor-android/master/README.md` (fetched 2026-10-26):

```kts
maven { uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
...
implementation("info.guardianproject:tor-android:0.4.9.13.2")
implementation("info.guardianproject:jtorctl:0.4.5.7")
```
The README's copy-paste block lags the registry by one patch (`0.4.9.13.2` vs published `0.4.9.14`). README also states: min **API 24**, ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64` ("`x86` support is possible but is off by default"), and requires `INTERNET` (+ `ACCESS_LOCAL_NETWORK` only if you expose SOCKS to the LAN and target API 37).

**A1.3 — The gpmaven mirror the README tells you to use is STALE.** `https://raw.githubusercontent.com/guardianproject/gpmaven/master/info/guardianproject/tor-android/maven-metadata.xml` (fetched 2026-10-26) → `<latest>0.4.8.16</latest>`, `<lastUpdated>20250516135208</lastUpdated>` (**2025-05-16**). ⇒ Do **not** rely on gpmaven for `0.4.9.x`; use `mavenCentral()`. This is a concrete, easily-missed build breaker (conflicting `maven-metadata.xml` per repository).

**A1.4 — Bundled Tor version + the security context.** README version table (fetched 2026-10-26): `tor 0.4.9.14` (linking `https://forum.torproject.org/t/security-release-0-4-9-14/22241`), `libevent 2.1.13`, `OpenSSL 3.5.9`, `zlib 1.3.2`, `zstd 1.5.7`. `CHANGELOG.md`: *"## 0.4.9.14 - October 8, 2026 — Update to `tor` 0.4.9.14"*. Upstream forum (dgoulet, **October 8, 2026**): *"Yesterday, we released stable version **0.4.9.14** to address a series of **high-severity security issues**… These issues affect all entities that is clients, onion services, directory authorities and relays… We **strongly** recommend updating as soon as possible."* ⇒ Positive: the AAR re-shipped the security fix within ~1 day. Risk: Tor is on a "rapid release cadence" (same post), so pinning is required.

**A1.5 — Artifact inventory (verified by download).** Fetched `https://repo1.maven.org/maven2/info/guardianproject/tor-android/0.4.9.14/tor-android-0.4.9.14.aar`:
- AAR size **12,976,254 bytes**; SHA-256 `28ff2bb6d4d42eabe57183dedd7a97fce77e8ce4b6f5af6fa03993b3ca054edd`
- `jni/arm64-v8a/libtor.so` **7,580,256 B** (sha256 `67ce86a6…96`), `jni/armeabi-v7a/libtor.so` **6,819,684 B**, `jni/x86/libtor.so` **7,229,832 B**, `jni/x86_64/libtor.so` **8,445,824 B** (sha256 `dc7f0fec…80`)
- `classes.jar` is tiny (11,756 B): only `org/torproject/jni/TorService.class` (+ inner classes, `BuildConfig`). **jtorctl is not inside the AAR**; it arrives as a transitive dependency.

**A1.6 — Transitive surface (POM, fetched).** `tor-android-0.4.9.14.pom` declares exactly 3 dependencies: `androidx.localbroadcastmanager:localbroadcastmanager:1.1.0`, `info.guardianproject:jtorctl:0.4.5.7`, `org.jetbrains.kotlin:kotlin-stdlib:2.2.10`. Note the AAR carries its own Kotlin stdlib pin (2.2.10) — older than current stable 2.4.21 (B-section); harmless but worth an explicit resolution strategy.

**A1.7 — `jtorctl` is frozen.** `https://repo1.maven.org/maven2/info/guardianproject/jtorctl/maven-metadata.xml` → `<release>0.4.5.7</release>`, `<lastUpdated>20210504074717</lastUpdated>` (**2021-05-04**); only 3 versions ever (`0.4`, `0.4.2.5`, `0.4.5.7`). It is a stable-but-unmaintained control-port client.

**A1.8 — Modern integration extras in README.** For `targetSdk > 36` (Android 17) the README adds `ACCESS_LOCAL_NETWORK` and links `https://developer.android.com/privacy-and-security/local-network-permission` — evidence upstream is tracking current platform changes, not just tor bumps.

**A1.9 — NetCipher (the "official" Orbot integration helper) is stale.** `https://repo1.maven.org/maven2/info/guardianproject/netcipher/netcipher/maven-metadata.xml` → `<release>2.2.0-alpha</release>`, `<lastUpdated>20210506104102</lastUpdated>` (**2021-05-06**). Its `OrbotHelper.java` still matches Orbot's current constants (A7.2), but the library itself has not been released in >5 years — plan to hand-roll the ~200 lines rather than depend on it.

---

## A2. Licenses

| Component | License (verified) | Evidence |
|---|---|---|
| `tor-android` | **BSD-3-Clause** | POM `<licenses><license><name>The 3-Clause BSD License</name><url>https://opensource.org/license/bsd-3-clause</url>` (A1.6). Repo `LICENSE` file opens with *"Orbot is distributed under this license (aka the 3-clause BSD license)"* and contains a *"Tor is distributed under this license:"* stanza for the bundled Tor code. |
| Tor daemon (`tor`) | **BSD-3-Clause** | Reproduced inside `tor-android/LICENSE` ("Tor is distributed under this license:", followed by the 3-clause text). Direct fetch of `gitlab.torproject.org/tpo/core/tor/-/raw/main/LICENSE` was **403** (GitLab blocks unauthenticated raw) → cite via the bundled copy; see §10. |
| `jtorctl` | BSD-3-Clause (same POM family) | `UNVERIFIED` by direct LICENSE fetch; it is a Guardian Project repo and the AAR/POM family consistently declares BSD-3. |
| Orbot | **BSD-3-Clause** | `https://raw.githubusercontent.com/guardianproject/orbot-android/master/LICENSE` → *"Orbot is distributed under this license (aka the 3-clause BSD license) … Copyright (c) 2009-2026, Nathan Freitas, The Guardian Project"* (fetched 2026-10-26). |
| Arti (`arti-client`) | **MIT OR Apache-2.0** | crates.io API `https://crates.io/api/v1/crates/arti-client` (fetched 2026-10-26): every recent version carries `license: "MIT OR Apache-2.0"`. |
| `tor` Flutter plugin (Arti-based) | **MIT** | pub.dev `https://pub.dev/api/packages/tor` → latest 0.2.2; repo `github.com/Foundation-Devices/tor` API: `license.spdx_id = MIT`. |
| Flutter "tor" plugin dependency chain | MIT (plugin) + MIT/Apache-2.0 (Arti) + BSD-3 (Flutter) | same sources |

**A2.1 — Copyleft/GPL risk: none.** All three options are permissive (BSD-3 / MIT / Apache-2.0). Orbot's `LICENSE` lists other bundled components (Privoxy, iptables) for *its own* app; embedding `tor-android` does not inherit those.
**A2.2 — Attribution obligation:** BSD-3 requires reproducing the copyright notice + conditions in app documentation (About/Licenses screen). Add a NOTICE entry for Guardian Project + Tor Project.

---

## A3. Maintenance

**A3.1 — Release cadence (from the Maven Central directory listing dates, A1.1).** Latest **2026-10-09**. Releases in the trailing 12 months (2025-10-26 → 2026-10-26): **10** —
`0.4.8.21` 2025-12-02, `0.4.8.21.1` 2025-12-17, `0.4.8.21.2` 2026-01-27, `0.4.8.22` 2026-01-30, `0.4.9.11` 2026-06-30, `0.4.9.12` 2026-09-18, `0.4.9.13` 2026-09-28, `0.4.9.13.1` 2026-10-06, `0.4.9.13.2` 2026-10-06, `0.4.9.14` 2026-10-09.

**A3.2 — Repo activity.** `https://api.github.com/repos/guardianproject/tor-android` (fetched 2026-10-26): `open_issues_count: 24`, `pushed_at: 2026-10-21`, `stargazers_count: 294`, `forks_count: 82`, `archived: false`, `license.spdx_id: NOASSERTION` (GitHub can't classify the Orbot-style LICENSE file — the POM's BSD-3 is authoritative).

**A3.3 — 16 KB was fixed upstream deliberately.** Issue **#170 "min NDK version in Makefile for 16 KB page sizes"** (opened 2025-07-01, **closed 2025-08-08**), body: *"This bumps the NDK up to r28 as per https://github.com/guardianproject/orbot-android/issues/1361"*. Corroborated empirically in §A4.

**A3.4 — Negative: an open native-crash cluster.** Open issues as of 2026-10-26 include **#184** *"Native crash: FORTIFY: pthread_mutex_lock called on a destroyed mutex"* (opened 2026-02-03, 0 comments, Play-crash-report, `org.torproject.android`, Android 11/SDK 30), **#185** *"tor_raw_abort native common crash"* (2026-02-04), **#167** *"Tor Raw Abort Crash"* (2025-06-16); mitigation PR **#190 "Recover From Arbitrary Tor Crashes"** is **still open** (opened 2026-03-01). ⇒ The native library can abort; your app must survive a `:tor`-process death and restart the service (see A5.6).

**A3.5 — Negative: long-standing API/robustness issues.** **#64** *"document how to use TorService with foreground priority via binding"* — open since **2021-12-16** (there is no documented FGS recipe; you must design it yourself). **#152** *"Socks port doesn't work if Orbot is running"* — open since 2024-09-25 (coexistence with Orbot is not supported/tested). **#73** *"Incorrect Shutdown Status Broadcasts"* (open, 2022-03). **#56** NPE when accessing `TorControlConnection` too early (open, 2021-11). **#80** crashes on slow devices when obtaining the control socket FD (open, 2022-06).
**A3.6 — Positive: bus factor is not 1.** Recent merged work is from multiple contributors (PRs #195 elf-cleaner 3.0.1, #198 pinned jar timestamps for reproducible builds, #199 secure random seed, #201 NDK path), and Orbot ships the same library, so breakage surfaces fast.

---

## A4. Android platform compliance — 16 KB page sizes (**empirical**)

**A4.1 — The requirement (current text, fetched 2026-10-26).** `https://developer.android.com/guide/practices/page-sizes` → *"all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play. **Starting February 1, 2027**, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates."* (Note: the earlier 2025-11-01 date has moved; the live page says **2027-02-01**.) The page also requires AGP **≥ 8.5.1** and `zipalign -c -P 16 -v 4`; and warns about `GNU_RELRO` (A4.4).

**A4.2 — What I parsed.** File: `F:\PyApps\ddandroid\EVIDENCE\research\artifacts\tor-android-0.4.9.14.aar` (sha256 `28ff2bb6…4edd`), members `jni/<abi>/libtor.so`. Script: `EVIDENCE/research/scripts/parse_elf_palign.py` (stdlib `zipfile`+`struct`, ELF64/ELF32 program-header parser). Output: `…aar.elf-scan.json`.

| ABI | ELF class | PT_LOAD count | min `p_align` | 16 KB ELF alignment |
|---|---|---|---|---|
| `arm64-v8a` | ELF64 | 3 | **0x4000 = 16384** | **PASS** |
| `x86_64` | ELF64 | 3 | **0x4000 = 16384** | **PASS** |
| `armeabi-v7a` | ELF32 | 3 | 0x1000 = 4096 | n/a (32-bit; Play rule covers 64-bit ABIs) |
| `x86` | ELF32 | 3 | 0x1000 = 4096 | n/a (32-bit; and upstream is dropping x86 from default builds) |

`arm64-v8a` program headers (verified): `off 0x0 → vaddr 0x0` (filesz 0x6d0830, all `p_align 0x4000`), `off 0x6d0830 → vaddr 0x6d4830` (RW), `off 0x72a968 → vaddr 0x732968` (RW). All segments are congruent (`p_offset % p_align == p_vaddr % p_align`).

**A4.3 — `p_align` alone is not sufficient; `GNU_RELRO` must also be sane.** The doc: *"A RELRO section having a start address plus segment size (MemSize) that is not 16 KB aligned crashes the app at runtime with a segmentation fault"*, with one exception (A4.4). Script `EVIDENCE/research/scripts/check_relro.py` + `inspect_relro_layout.py`, output `…aar.relro-scan.json` / `…relro-layout-*.json`:

| ABI | `GNU_RELRO` range | end mod 0x4000 | RELRO is its own PT_LOAD? | next writable LOAD | verdict |
|---|---|---|---|---|---|
| `arm64-v8a` | 0x6d4830 + 0x5a7d0 → 0x72f000 | 0x3000 | **yes** (`LOAD` vaddr/memsz identical) | 0x732968 (page 0x730000) | **compliant via exception** |
| `x86_64` | 0x7a5300 + 0x5cd00 → 0x802000 | 0x2000 | **yes** | 0x805910 (page 0x804000) | **compliant via exception** |

**A4.4 — Why that passes.** The doc's aligned example states: *"Although 0x1000 isn't zero, this .so file is still 16 KB compliant… Because the entire LOAD segment contains only RELRO data, there's no writable data in the sub-range… Additionally, any subsequent writable LOAD segment begins on a separate 16 KB page boundary."* That is exactly the layout measured: the RELRO-bearing `PT_LOAD` ends at 0x72f000 (last section `.relro_padding` 0x72e968–0x72f000), and `.data` begins at 0x732968 — i.e. in the **next** 16 KiB page (0x730000), not the RELRO-end page. Same for x86_64 (`.data` at 0x805910, page 0x804000, vs RELRO end page 0x800000).

**A4.5 — Verdict.** `libtor.so 0.4.9.14` is **16 KB-compatible for the 64-bit ABIs**; the app can meet the **2027-02-01** Play requirement with this AAR. Remaining obligations are app-side, not library-side: AGP ≥ 8.5.1 (recommend current stable 9.4.1), keep `.so` **uncompressed** (do *not* set `jniLibs.useLegacyPackaging = true`), verify with `zipalign -c -P 16 -v 4`, and run a 16 KB emulator/image smoke test (`adb shell getconf PAGE_SIZE` → 16384).

**A4.6 — If it had failed, the fix (documented):** NDK **r28+** compiles 16 KB-aligned by default; NDK ≤ r27 needs `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`; packaging per A4.5. Since we cannot rebuild upstream AARs, the fallback would be to build `tor-android` from source via its `./tor-droid-make.sh build` (NDK `30.0.16248370` per README) — costly; **not needed here**.

**A4.7 — Upstream 16 KB tracker state.** GitHub issue search (`repo:guardianproject/tor-android 16 KB page size`, fetched 2026-10-26) → `total_count: 2`, both closed: #170 (NDK r28 bump, closed 2025-08-08) and an unrelated 2019 revert. No open 16 KB issues.

---

## A5. Runtime / API (how the app actually drives it)

**A5.1 — `TorService` is the only public class shipped.** AAR `classes.jar` entries (verified by extracting the AAR): `org/torproject/jni/BuildConfig.class`, `TorService.class`, `TorService$1`.class, `TorService$1$1.class, `TorService$2.class`, `TorService$LocalBinder.class`. Everything else is native or via jtorctl.

**A5.2 — Start/stop/status contract (source, fetched 2026-10-26).** `tor-android-binary/src/main/java/org/torproject/jni/TorService.java`:
- `ACTION_START = "org.torproject.android.intent.action.START"`, `ACTION_STOP = "…action.STOP"`, `ACTION_STATUS = "…action.STATUS"`, `ACTION_ERROR = "…action.ERROR"`
- `EXTRA_STATUS = "…extra.STATUS"`; `STATUS_ON/OFF/STARTING/STOPPING`, plus `STATUS_CLIENT_CIRCUIT_ESTABLISHED = "CIRCUIT_ESTABLISHED"` and `EXTRA_SERVICE_PACKAGE_NAME` (so receivers can tell which app broadcast)
- stats are broadcast **both** via `LocalBroadcastManager` and a normal `context.sendBroadcast(...)`; `onStartCommand` re-broadcasts current status (`sendBroadcastStatusIntent`) — i.e. status is readable by polling a sticky-style broadcast or by binding (`LocalBinder`).
- port discovery is dynamic: `getPortFromGetInfo("net/listeners/socks")` / `"net/listeners/httptunnel"`, with a fallback `if (isPortAvailable(9050)) socksPort = 9050`.

**A5.3 — Bootstrap progress.** Two granularities: coarse lifecycle via the broadcasts above (`STARTING` → `CIRCUIT_ESTABLISHED` → `ON`), and fine-grained percentage only through the Tor **control port** (jtorctl `TorControlConnection` / `getInfo()`), which requires binding to the service (`LocalBinder`) and its control socket. Known pitfalls from the tracker: `getPortFromGetInfo` NPE when `getInfo()` fails (#197 PR open), `NullPointerException when accessing TorControlConnection too early` (#56 open), `crashes on slow devices when obtaining the control socket's file descriptor` (#80 open). ⇒ Treat "bootstrap %" as optional UI; gate on `ON`/`CIRCUIT_ESTABLISHED` for fail-closed logic. `UNVERIFIED`: exact bootstrap-percent API surface (I did not decompile `classes.jar`).

**A5.4 — Default ports.** SOCKS `9050` (fallback), HTTP tunnel via `net/listeners/httptunnel`, control port dynamic. Route **all** egress through the SOCKS port; never fall back to direct.

**A5.5 — The AAR ships no foreground service.** `TorService` is a plain `Service` in the library; Android 8+ background-start limits and Android 12+ FGS rules mean **your app** must own a foreground service (declare `android:foregroundServiceType="dataSync"`, `FOREGROUND_SERVICE_DATA_SYNC` permission) and keep TorService alive from there. Android 15 caps `dataSync` at **6 h per 24 h** and then calls `Service.onTimeout(int,int)` (quoted in §B4.4 of `research-stack.md`) — design the restart/timeout path, and use a user-initiated "download" action as the FGS start trigger.

**A5.6 — Kill/restart behaviour is a real risk.** Native aborts exist (A3.4). `TorService` runs in-process by default; Orbot explicitly isolates it with `android:process=":tor"` (`orbot-AndroidManifest.xml`). Recommendation: run it in a dedicated process, supervise it, and treat "Tor process died" as a hard stop of the download (fail-closed), not a silent retry on clearnet.

**A5.7 — DNS-leak behaviour with a SOCKS proxy (verified in OkHttp source).** `https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RouteSelector.kt` (fetched 2026-10-26):
```kotlin
if (proxy.type() == Proxy.Type.SOCKS) {
  routes += Route(proxy, InetSocketAddress.createUnresolved(socketHost, socketPort), …)
```
`createUnresolved` ⇒ the **hostname is handed to the SOCKS proxy unresolved**; DNS is resolved by Tor (remote), so no local resolver query is made for proxied requests. ⇒ Configure OkHttp with `Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))` plus a `ProxySelector` that **throws/returns null for non-Tor routes only if fail-closed is enforced** — note OkHttp treats a *null* route list as "no route" (failure), which is the fail-closed behaviour you want; a `DIRECT` fallback is the leak. Additional leak vectors to close: WebView (must be proxied or not used), `DownloadManager`/system components (not proxied — do your own HTTP), and any DNS-over-HTTPS/UDP path.

---

## A6. Arti (Rust) — can an APK embed it today?

**A6.1 — Version/activity (crates.io, fetched 2026-10-26).** `arti-client` latest **0.47.0 (2026-10-01)**, 12 releases in the last 12 months, 1,160,366 all-time downloads, license `MIT OR Apache-2.0`; `arti` binary crate latest **2.7.0 (2026-10-01)**. GitLab project `tpo/core/arti`: last activity 2026-10-26. Upstream README (fetched via GitLab API): *"Arti is a full-featured Tor client… We believe that Arti is suitable for general client usage. Currently (as of August 2026) we're working on support for running Arti as a relay or directory authority."*

**A6.2 — The Android story is stale and explicitly DIY.** `doc/Android.md` (GitLab API, fetched 2026-10-26): *"**Limitations** — At the moment of writing this guide, Arti does not have a stable Rust API yet. For that reason, **no proper bindings are provided. You'll need to write these bindings yourself using the Java Native Interface.** (If this doesn't sound like something you can do, you might be better off waiting until Arti is more stable.)"* Also: *"NDK 23 is not supported yet"*, `[lib] crate-type = ["dylib"]`, and manual `System.loadLibrary`. **Doc freshness:** commits touching `doc/Android.md` end at **2022-04-20** (`142e57b0 Fix typos`) — 4.5 years stale.
**A6.3 — The only sample is abandoned.** `doc/Android.md` links `gitlab.torproject.org/trinity-1686a/arti-mobile-example`; GitLab API: `created_at 2021-09-14`, **`last_activity_at 2022-08-19`**.
**A6.4 — No maintained JVM/Android binding exists** (no `arti-android`, `arti-jni`, or Kotlin AAR found via crates.io/GitLab/Maven searches performed this session — `UNVERIFIED` as a negative: absence of search hits is weak evidence).
**A6.5 — Mobile-relevant open Arti issues.** #354 *"Supported mobile APIs for arti"* (opened 2022-02-22, **open**); #71 *"'Dormant' mode for mobile/embedded clients"* (2021-02-05, **open**); #2820 *"guards.json corrupted to be empty after phone crash"* (2026-10-21, open — directly mobile); #2544 *"try_lock is not supported on Android"* (closed 2026-05-23); #2741 rusqlite pin blocking embedding (closed 2026-09-22); #925 *"Non-fatal failures to bootstrap don't provide embedding code with any details"* (2023, open).
**A6.6 — Verdict.** Arti **cannot be embedded in an APK today without a bespoke Rust cross-compile + JNI/FFI layer + your own lifecycle/bootstrap wrappers** (plan: Rust toolchain, `cargo-ndk`, 4 ABIs, `jni` crate, error mapping, cancellation). That is a multi-week platform project with a 0.x API surface that reserves the right to break. Not a milestone-1 option. (The existence of a **Flutter** plugin wrapping Arti, `tor 0.2.2`, does not help a Kotlin app.)

---

## A7. Orbot (external app)

**A7.1 — Orbot is the same native code, packaged as its own app.** `guardianproject/orbot-android` API (2026-10-26): `pushed_at 2026-10-26`, `open_issues_count 81`, 3,654 stars, not archived; latest release tag **`17.9.7-RC-1-tor-0.4.9.14`** (published 2026-10-09; note the top of the train is RC/BETA). Its manifest declares `org.torproject.jni.TorService` with `android:process=":tor"`, `OrbotService` `foregroundServiceType="systemExempted"`, `FOREGROUND_SERVICE_DATA_SYNC`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `BIND_VPN_SERVICE`, and `<queries>`.

**A7.2 — Integration contract (source-verified).** `orbot-OrbotConstants.kt`: `SOCKS_PROXY_PORT_DEFAULT = "9050"`, `HTTP_PROXY_PORT_DEFAULT = "8118"`, `TOR_DNS_PORT_DEFAULT = 9053`, `TOR_TRANSPROXY_PORT_DEFAULT = 9040`, `EXTRA_SOCKS_PROXY{,_HOST,_PORT}`, `EXTRA_HTTP_PROXY…`, `LOG_NOTICE_BOOTSTRAPPED = "BOOTSTRAP PROGRESS="`, `PREFS_KEY_TORIFIED`. `netcipher/…/OrbotHelper.java` (fetched): `ACTION_START = "org.torproject.android.intent.action.START"`, `ACTION_STATUS = "…action.STATUS"`, `EXTRA_PACKAGE_NAME`, `EXTRA_STATUS`, `STATUS_ON/OFF/STARTING/STOPPING`, **`STATUS_STARTS_DISABLED`**, `ACTION_START_TOR = "org.torproject.android.START_TOR"`, `DEFAULT_PROXY_SOCKS_PORT = 9050`. Javadoc: *"That reply ACTION_STATUS Intent could say that the user has disabled background starts with the status STATUS_STARTS_DISABLED. That means that Orbot ignored this [request]"*.

**A7.3 — The doc for this is an ARCHIVED wiki page.** `https://guardianproject.gitlab.io/public-content/dev/projects/orbot/wiki/Orbot_Intent_API.html` — title says **"(ARCHIVED SITE)"**, footer "© 2006-2013", but the described contract still matches current `OrbotConstants.kt`/`OrbotHelper.java` (A7.2). Treat the page as indicative, the source as authoritative.

**A7.4 — Negative (decisive for fail-closed):** (a) Orbot may not be installed; (b) it can be force-stopped/disabled or have background starts refused (`STATUS_STARTS_DISABLED`); (c) on Android 11+ your app must declare `<queries><package android:name="org.torproject.android"/></queries>` to even detect it (package-visibility filtering; NetCipher's `OrbotHelper` calls `PackageManager.getPackageInfo(...)`); (d) VPN mode is a user-level, whole-device/per-app choice — if the user does not enable "Tor-enabled apps"/VPN for your app, your sockets go **direct** and there is no API to detect-and-prevent that from inside the app; (e) issue #637 *"Orbot no longer broadcasts a status message if it is started by an ACTION_START intent"* (in the search index, fetched 2026-10-26) shows status delivery has regressed before. ⇒ An app can require "Orbot is ON and SOCKS 9050 accepts connections" before enabling downloads — that *is* implementable fail-closed — but it cannot guarantee Tor stays up, and it cannot stop the user from running the app in a state where the app itself has no route.

---

## A8. Recommendation inputs — ranked comparison

Weights: **leak control 30**, **background reliability 20**, self-containedness 15, maintenance risk 15, APK size 10, license 10 (leak + reliability = 50% because this is a security/UX-critical choice).

| Criterion (weight) | 1. Embedded `tor-android` | 2. Embedded Arti | 3. Orbot (external) |
|---|---|---|---|
| Leak control / fail-closed (30) | **5** — in-process, you own `ProxySelector`; OkHttp passes hostnames unresolved to SOCKS ⇒ remote DNS (A5.7); a dead Tor = dead socket = hard stop | **5** — same, plus Rust memory safety; but you must write the bindings and the SOCKS listener yourself | **3** — fail-closed *only if* user enables VPN/app-routing or your app strictly gates on "Orbot ON + 9050 reachable"; cannot prevent user-side misconfig; `STATUS_STARTS_DISABLED` |
| Background reliability (20) | **4** — you own a `dataSync` FGS; but 6 h/24 h cap (Android 15) + open native aborts (#184/#185) require supervision/restart | **2** — no Android lifecycle guidance, dormant-mode API still open (#71), no bindings | **4** — Orbot owns a `systemExempted` FGS + battery-exemption prompt; survives better than a plain FGS, but is a separate killable app |
| Self-containedness (15) | **5** — single Gradle dep; no Play-store companion | **5** — single APK (after you build the layer) | **1** — hard dependency on a second app the user must install |
| APK size (10) | **3** — +12,976,254 B all-ABI AAR; +7,580,256 B if arm64-only split (A1.5) | **3** — Rust `arti-client` graph + 4 ABI builds; exact size `UNVERIFIED` (no build attempted) | **5** — 0 B |
| Maintenance risk (15) | **4** — 10 releases/12 mo, security fix shipped in ~1 day (A1.4), but open crashes + frozen jtorctl | **2** — 0.x API, 2022 Android guide, abandoned sample, open mobile issues | **4** — very active (pushed 2026-10-26), but its ABI is out of your control |
| License cleanliness (10) | **5** — BSD-3 (A2) | **5** — MIT/Apache-2.0 | **5** — BSD-3 |
| **Weighted total /500** | **445 (89%)** | **375 (75%) — GATED** | **345 (69%)** |

**Recommendation: option 1 — embed `info.guardianproject:tor-android`.** Decisive evidence, in order: (i) artifact is on Maven Central at a version published 2026-10-09 that contains the 2026-10-08 high-severity Tor fix (A1.1/A1.4); (ii) 16 KB compliance verified **by parsing the shipped binary**, not by trusting a changelog (A4.2–A4.5), so the Play 2027-02-01 gate is met; (iii) BSD-3 and it is what Orbot itself ships (A2/A7.1); (iv) leak control is fully in your hands and OkHttp's SOCKS path gives remote DNS by construction (A5.7).

**Runner-up: option 3 (Orbot).** It loses on exactly one axis, but it is the axis that matters: **self-containedness/fail-closed authority**. Concretely, `STATUS_STARTS_DISABLED` (A7.2) means the user can put Orbot in a state where your app *cannot start Tor*, and there is no API to force or verify VPN routing of your own sockets — your only lever is refusing to download, which is weaker than owning the transport. It would become the right answer if the product direction is "power users who already run Orbot" and APK size/policy review dominates.

**Arti is not the runner-up; it is gated.** It scores 75% on paper because isolation would be ideal, but A6.2–A6.6 are hard blockers (no bindings, stale guide, abandoned sample). Revisit when Tor Project publishes maintained Android bindings.

### What would change this decision
- If Tor is **not** actually required for the product (see A0.1), ship **no Tor** — that is the cheapest, safest outcome, and the AAR's 12.98 MB + FGS + leak surface disappear.
- If the app must also route **torrent/DHT** traffic (not just HTTP API calls), `tor-android`'s SOCKS alone is insufficient (DHT/UDP does not traverse SOCKS5 reliably) → Orbot **VPN mode** becomes attractive despite A7.4.
- If Tor Project ships official `arti` Android bindings (track #354 / the Android guide), Arti jumps to first place on leak control + memory safety.
- If Play policy/region considerations forbid bundling a Tor binary, Orbot becomes the only compliant route.

---

## A9. Concrete integration recipe (for the Lead, from the evidence above)

```kotlin
// settings.gradle.kts / build.gradle.kts repositories
repositories {
    mavenCentral()                       // serves 0.4.9.14 (A1.1) — do NOT rely on gpmaven (stale, A1.3)
}
dependencies {
    implementation("info.guardianproject:tor-android:0.4.9.14")  // pin; README's 0.4.9.13.2 lags (A1.2)
    // jtorctl 0.4.5.7 arrives transitively (A1.6)
}
```
- Manifest: `INTERNET`; FGS `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS`.
- Start `TorService` (explicit intent) from a **user-initiated** action inside your own `dataSync` FGS; observe `ACTION_STATUS` broadcasts (A5.2) and treat anything but `ON` as "no network".
- OkHttp: `proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))` with `socksPort` from the status/`getInfo` path, never hardcoded-only (A5.2/A5.7). No `DIRECT` fallback.
- Verify build: `zipalign -c -P 16 -v 4 app.apk`, `adb shell getconf PAGE_SIZE` on a 16 KB image (A4.5).
- Add BSD-3 NOTICE (A2.2). Pin + monitor: Tor security releases (A1.4), tor-android releases (A3.1), issues #184/#185/#190 (A3.4).

---

## A10. UNVERIFIED / gaps

1. **`tor` daemon LICENSE direct fetch failed** (GitLab raw → 403). BSD-3 is corroborated by the copy embedded in `tor-android/LICENSE` (A2) — not by fetching `tpo/core/tor/LICENSE`.
2. **`jtorctl` LICENSE** not fetched directly (`UNVERIFIED`; inferred BSD-3 from the same project family).
3. **Bootstrap-percentage API**: I did not decompile `classes.jar`; the percentage path via jtorctl control connection is inferred from `TorService.getPortFromGetInfo(...)` + known control-port semantics. Only the coarse status constants are source-verified (A5.2/A5.3).
4. **No on-device 16 KB smoke test** was run (no emulator/device on this machine: `adb`, `gradle`, `flutter` not installed). The 16 KB verdict is **static ELF + documented-rule** evidence, which is what Play's check is based on, but a boot test is still recommended (A4.5).
5. **Arti APK size and build feasibility** not measured — no Rust toolchain build attempted (`UNVERIFIED`).
6. **Orbot's current Play Store build** (as opposed to GitHub release tags, which top out at `17.9.7-RC-1`) was not fetched (`UNVERIFIED`); F-Droid API returned 404 for `org.torproject.android` during this session.
7. GitHub **search** API rate limit (403) blocked a few issue searches; retried succeed later. Counts quoted are from `open_issues_count` and explicit API calls dated 2026-10-26.
8. `tor-android` repo license metadata is `NOASSERTION` on GitHub (the LICENSE file is an Orbot-style composite); BSD-3 provenance rests on the POM + LICENSE text (A2).

---

## A11. Skills applied (this document)

| Skill | Path | What it changed (precise) |
|---|---|---|
| `deep-research` | `F:\PyApps\agent-skills\.agent\skills\deep-research\SKILL.md` | Forced primary-source-only, dated, quoted claims and an explicit negative-evidence hunt. Changed the Arti verdict from "promising" to **GATED**: it drove me to fetch `doc/Android.md` + its commit history (last touch 2022-04-20) and the sample project's `last_activity_at` (2022-08-19) instead of relying on the README's positive status paragraph. Also produced §A10 (limitations) rather than silently omitting gaps. |
| `supply-chain-security` | `…\skills\supply-chain-security\SKILL.md` (its §"第三方依赖审查" checklist) | Applied the "maintenance status / dependency tree / license" review to each candidate. Concrete change: **A1.3** — discovered the gpmaven mirror's metadata is stale (latest 0.4.8.16, 2025-05-16) while the README instructs adding it; the recipe in §A9 therefore uses `mavenCentral()` and pins 0.4.9.14. Also enumerated the AAR's 3 transitive deps + the >5-year-old NetCipher/jtorctl (A1.6–A1.9) instead of treating the AAR as a black box. |
| `security-and-hardening` | `…\skills\security-and-hardening\SKILL.md` | Applied its "threat model first / map trust boundaries / fail closed" process to the Tor transport. Concrete changes: added **A5.5–A5.7** (own FGS, process isolation, and the *no-DIRECT-fallback* requirement derived from OkHttp's `createUnresolved` SOCKS path) and made "fail-closed" an explicit scoring axis in A8 rather than a footnote; also added A0.1 (scope check) so we don't add a security-critical component for non-existent parity. |
| `privacy-by-design` | `…\skills\privacy-by-design\SKILL.md` | Data-minimization applied to the Tor path: §A9 constrains the integration to *no magnet/API-key logging* and local-only state; A8's leak-control weighting (30%) encodes "privacy as default" (Tor off unless the user explicitly enables it, but then always fail-closed). |
| `architecture-decision-records` | `…\skills\architecture-decision-records\SKILL.md` | Imposed the context → drivers → options → decision → consequences → *what would change the answer* structure (A8 "What would change this decision"), and the GATED status for Arti instead of silently ranking it. |
