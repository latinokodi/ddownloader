# ENVIRONMENT — Android toolchain, emulator, and Electron ground truth

Owner: `env-runner` (teammate). Machine: Windows 11 host `PEGASUS`, user `MrAnderson`.
Scope: this file records **only what was executed and observed** on this host. Anything not
observed is marked UNVERIFIED.

Last updated: 2026-10-26 (UTC-05:00).

---

## 0. TL;DR status

| Item | Status | Evidence |
|---|---|---|
| JDK 21 (`JAVA_HOME`) | OK | `openjdk 21.0.9 2025-10-21 LTS` (Microsoft build) |
| Android SDK at `C:\Android\Sdk` | OK | 5 packages installed, see §1 |
| `adb` | OK | `Android Debug Bridge version 1.0.41 / 37.0.1-15733141` |
| `emulator` | OK | `Android emulator version 37.2.12.0` |
| AVD `dd35` (API 35, x86_64, Pixel 6) | OK | §2 |
| Headless boot | OK | `sys.boot_completed=1` after **45.53 s** (device visible after 18.73 s) |
| Emulator TCP/DNS egress | OK | HTTP 301 from `check.torproject.org:80`; HTTP 200 from host fixture |
| Emulator ICMP ping | **FAILS (expected)** | emulator slirp does not forward ICMP echo — see §4 |
| Device → host networking | OK via `10.0.2.2` | HTTP 200 + HTTP 206 Range verified |
| `adb reverse` / `adb forward` | **BROKEN on this host** | TCP connects, 0 payload bytes — see §5 |
| `adb root` | **YES** | `uid=0(root) ... context=u:r:su:s0`; `/proc/net/tcp` readable |
| Gradle | absent by design | project must use the Gradle wrapper (not installed here) |
| Electron ground truth | captured | see §7 and `EVIDENCE/electron/` |

The emulator **is currently running** and booted as root on `emulator-5554`.

---

## 1. Android SDK inventory

Installed with `sdkmanager` 19.0 (`C:\Android\Sdk\cmdline-tools\latest`), licenses accepted.

```
Path                                        | Version | Description
build-tools;35.0.0                          | 35.0.0  | Android SDK Build-Tools 35
emulator                                    | 37.2.12 | Android Emulator
platform-tools                              | 37.0.1  | Android SDK Platform-Tools
platforms;android-35                        | 2       | Android SDK Platform 35
system-images;android-35;google_apis;x86_64 | 9       | Google APIs Intel x86_64 Atom System Image
```

Notes:
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` are **not** persisted in the user environment. Export them per session (see §2).
- The first `sdkmanager` install run ended with `Warning: Failed to download package!` exit 1 and left
  `build-tools/35.0.0` as an `.installer`-only stub. Re-running `sdkmanager "build-tools;35.0.0"` and
  `sdkmanager "platforms;android-35"` individually succeeded (exit 0). If a package directory exists but
  `sdkmanager --list_installed` does not list it, re-run that package alone.
- Hypervisor: `emulator -accel-check` → `WHPX(10.0.26200) is installed and usable.`

---

## 2. Exact commands (copy/paste)

### 2.1 Session environment

```powershell
$env:JAVA_HOME       = 'C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'
$env:ANDROID_HOME    = 'C:\Android\Sdk'
$env:ANDROID_SDK_ROOT= 'C:\Android\Sdk'
$env:PATH            = "C:\Android\Sdk\platform-tools;C:\Android\Sdk\emulator;C:\Android\Sdk\cmdline-tools\latest\bin;$env:PATH"
```

Convenience: dot-source `F:\PyApps\ddandroid\scripts\env-android.ps1` to get the above plus `$adb`
and a `Wait-EmulatorBoot` helper.

### 2.2 AVD creation (already done — do not repeat, it would wipe config)

```powershell
'no' | & C:\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat create avd `
  -n dd35 -k "system-images;android-35;google_apis;x86_64" -d pixel_6 --force
```

### 2.3 Start the emulator (exact command used)

```powershell
C:\Android\Sdk\emulator\emulator.exe -avd dd35 -no-window -no-audio -no-boot-anim `
  -no-snapshot -gpu swiftshader_indirect -accel on
```

Measured: `sys.boot_completed=1` in **45.53 s**; unique serial **`emulator-5554`** (console port 5554).

To run it as a background job instead of a detached process:

```powershell
Start-Process -FilePath 'C:\Android\Sdk\emulator\emulator.exe' -PassThru -WindowStyle Hidden `
  -ArgumentList @('-avd','dd35','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-gpu','swiftshader_indirect','-accel','on')
```

Wait for boot deterministically (script exists): `scripts\env-boot-wait.ps1` (polls
`sys.boot_completed`, not a fixed sleep) → writes `EVIDENCE\env\boot-timing.json`.

### 2.4 Stop the emulator

```powershell
C:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 emu kill
```

---

## 3. AVD config (`C:\Users\ferna\.android\avd\dd35.avd\config.ini`)

Applied deterministic overrides (original saved as `config.ini.original`):

| key | value |
|---|---|
| `hw.ramSize` | `4096` |
| `hw.gpu.enabled` | `yes` |
| `hw.gpu.mode` | `swiftshader_indirect` |
| `disk.dataPartition.size` | `12G` |
| `hw.keyboard` | `yes` |
| `snapshot.present` | `false` |
| `fastboot.forceColdBoot` | `yes` |
| `fastboot.forceFastBoot` | `no` |
| `firstboot.bootFromDownloadableSnapshot` | `no` |
| `firstboot.bootFromLocalSnapshot` | `no` |
| `firstboot.saveToLocalSnapshot` | `no` |

Device profile `pixel_6`, screen `1080x2400`, density `420`, no snapshots (cold boot every time).

---

## 4. Device facts (observed)

```text
serial             emulator-5554
Android release    15
SDK / API          35
ABI                x86_64
model              sdk_gphone64_x86_64
wm size            Physical size: 1080x2400
wm density         Physical density: 420
```

Network:
- `adb shell ping -c 2 8.8.8.8` → **FAILS**: `From 10.0.2.2: icmp_seq=1 Destination Net Unreachable`,
  100% packet loss. This is normal for the emulator's user-mode network (ICMP echo is not forwarded);
  it does **not** mean there is no internet.
- TCP + DNS egress **works**:
  `printf 'GET / HTTP/1.0\r\nHost: check.torproject.org\r\n\r\n' | toybox nc -w 8 check.torproject.org 80`
  → `HTTP/1.1 301 Moved Permanently`.
- `curl` and `wget` are **absent** on the image (`toybox 0.8.11-android`; `toybox wget` → Unknown command).
  Only `nc` exists, so **HTTPS could not be tested from the device shell**. Verify TLS from the app itself.
- Device → host: `http://10.0.2.2:<hostPort>` **works** and was verified against the fixture server:
  - `GET /health` → `HTTP/1.1 200 OK`, 1203-byte JSON body.
  - `GET /file/plain.bin` with `Range: bytes=0-31` → `HTTP/1.1 206 Partial Content`,
    `Content-Range: bytes 0-31/8388608`, 32 bytes.
  **Use `10.0.2.2` for device→host traffic during development/testing.**

---

## 5. adb reverse / forward — BROKEN on this host (verified)

Both directions establish the TCP connection but deliver **zero payload bytes**.

Evidence:
1. `adb reverse tcp:8787 tcp:8787` → returns `8787`; `adb reverse --list` shows the mapping;
   `ss -ltn` on the device shows `adbd` listening on `*:8787`.
2. Device connects (device-side `/proc/net/tcp` shows established/time-wait sockets to `127.0.0.1:8787`).
3. Host-side fixture server never logged the request; a raw PowerShell `TcpListener` on the host
   **accepted** the tunnel connection but `Read()` returned 0 bytes.
4. `adb forward tcp:18788 tcp:8555` → returns `18788`; device-side listener was verified listening
   (`ss -ltn` → `*:8555`); host `TcpClient.Connect` succeeded and wrote bytes; the device-side
   `nc` received **0 bytes**.
5. Retried after `adb kill-server`/`start-server` and after `adb unroot`; same result.
   `adb` 37.0.1, emulator 37.2.12, Windows 11 26200.

Conclusion: **do not rely on `adb reverse`/`adb forward`.** Use `10.0.2.2` from inside the emulator
(verified), or push/pull via `adb shell` (works).

---

## 6. Root

`adb root` → `restarting adbd as root`, then:

```
uid=0(root) gid=0(root) groups=0(root),1004(input),1007(log),1011(adb),... context=u:r:su:s0
```

`/proc/net/tcp` is readable as root (15 lines returned; entries include `0100007F:...` loopback and
`1002000A:...` = `10.0.2.15`). Root is therefore available for the `/proc/net/tcp` inspection the
Android port needs. Note: `adb unroot` reverts to `uid=2000(shell)`.

---

## 7. Electron ground-truth artifacts (Workstream B)

All under `F:\PyApps\ddandroid\EVIDENCE\electron\`:

| file | contents |
|---|---|
| `build-facts.txt` | package.json version/main/scripts, Electron 42.11.10 installed, 425 `node_modules` entries, prebuilt `dist-electron/*.js` + `dist/index.html` with sizes and mtimes, presence of compiled `downloader.js` |
| `app-launch.log` / `app-launch.err.log` | exact `npm start` stdout/stderr |
| `app-connections.json` | 30 s @500 ms sampling of every TCP connection owned by the Electron PID tree, plus stdout head, exit and cleanup |
| `diff-electron.json` / `.md` | the 5-scenario differential harness result (server request logs, Downloader result objects, final sizes, SHA-256) |
| `reference-hashes.json` | SHA-256 of every deterministic reference stream + the 3 MiB prefix hashes |
| `diff-server-requests.jsonl` | raw per-request JSONL log of the harness fixture server |
| `shim/SHIM-MANIFEST.txt` | provenance of the test-only shim (hashes, exact delta) |
| `run-log.txt` | chronological exact commands + observed results |

Key observed facts:
- App version **1.0.18**, `main = dist-electron/main.js`, `scripts.start = electron .`.
- `npm start` **works on this host**: 4 `electron` processes appeared and the renderer loaded.
  Stdout: `[Worker] Starting background worker (10s interval)`, FlareSolverr launched from
  `C:\Users\ferna\AppData\Roaming\tordownloader-pro\flaresolverr\...\flaresolverr.exe`,
  `[CF Fetcher] HTTP bridge listening on port 56042`, `[FlareSolverr] Ready on port 8191`.
  No stderr output. Cleanly killed afterwards (4 electron + 1 python child).
- Observed remote endpoints during the 30 s window: `127.0.0.1:8191` (local FlareSolverr),
  `94.140.4.10:443` and `81.85.62.11:443` (**PTR → real-debrid.com**),
  `104.20.28.56:443` (**resolves torbox.app / api.torbox.app**, Cloudflare).
- `pktmon` is present but **requires elevation**: `pktmon start --capture ...` →
  `Failed to communicate with the PktMon driver: Access is denied.` (exit 5). No ETL/PCAP was produced;
  the connection sampling in `app-connections.json` is the substitute evidence.

Downloader ground truth (details + full logs in `diff-electron.json`):

| scenario | server response | Downloader result | final size | matches reference |
|---|---|---|---|---|
| s1 fresh full (`plain.bin`) | 200, 8388608 B | `{success:true, bytes_downloaded:8388608}` | 8388608 | YES |
| s2 resume 3 MiB partial (`plain.bin`) | **206**, `Content-Range: bytes 3145728-8388607/8388608` | `{success:true, bytes_downloaded:8388608}` | 8388608 | YES — resume works |
| s3 server ignores Range (`norange.bin`) | 200 full body | `{success:false, error:"Range not supported by CDN", bytes_downloaded:3145728, rangeIgnored:true}` | 3145728 (partial preserved, not truncated) | n/a (by design) |
| s4 flaky cut after 4 MiB (`flaky.bin`) | 1st: 200, cut at 4194304 B · 2nd: **206** from 4194304 | 1st: **NEVER RESOLVED** (30 s timeout, `result=null`) · 2nd: `{success:true, bytes_downloaded:8388608}` | 8388608 | YES — retry resumes |
| s5 empty body, `Content-Length: 1000` (EXTRA) | 200, 0 body bytes | **NEVER RESOLVED** (30 s timeout, `result=null`) | 0 | no |

**Critical for the port:** a mid-stream socket cut and a truncated body both leave the original
`Downloader.start()` promise **unresolved** — no `DownloadResult` is produced. The shipped
"Truncated download" and writer-error branches are not reached on a source-stream error because
`response.data` has no `'error'` handler and `writer.on('finish')` never fires. Resume itself works
on a *retry* (fresh `Downloader` with the partial on disk → `Range` → 206 → append → exact SHA match).

---

## 8. Deterministic fixture contract (for the Android side)

`F:\PyApps\ddandroid\test-fixtures\fixture-server.js` — zero-dependency Node HTTP server.
Run: `node fixture-server.js --port 8787 --log fixture-requests.jsonl` (binds `127.0.0.1`).
From the emulator use `http://10.0.2.2:8787/...`.

Content is a **xorshift32** stream seeded per file name, so an independent Kotlin/Java
implementation must produce identical bytes and identical SHA-256:

```
seed(name) = (0x1234abcd ^ fnv1a32(name)) & 0xFFFFFFFF
fnv1a32(s): h = 2166136261; for each byte b: h = ((h ^ b) * 16777619) & 0xFFFFFFFF
x = seed(name)
for i in 0..len-1:
    x ^= (x << 13) & 0xFFFFFFFF
    x ^= x >>> 17
    x ^= (x << 5)  & 0xFFFFFFFF
    byte[i] = x & 0xFF
```

Endpoints: `/health`, `/file/plain.bin` (Range OK, 8388608 B), `/file/norange.bin` (ignores Range,
8388608 B), `/file/flaky.bin` (first request cut at 4194304 B, then normal), `/file/empty.bin`
(200 + `Content-Length: 1000`, 0 body bytes).
Reference SHA-256 values: `EVIDENCE\electron\reference-hashes.json` (no need to re-derive from the server).
See `test-fixtures\README.md` for the full request/response contract.

---

## 9. Gotchas / notes for the Lead

1. `ANDROID_HOME` is **not** persisted — always export it (or dot-source `scripts/env-android.ps1`).
2. The emulator must be started from a shell that has `ANDROID_SDK_ROOT` set, otherwise the system
   image is not found.
3. Emulator boot is deterministic here (~45 s cold). Use `scripts/env-boot-wait.ps1` rather than `sleep`.
4. `adb reverse`/`forward` are broken on this host — design host↔device testing around `10.0.2.2`.
5. `adb root` is available but `adb unroot` may be needed if a tool refuses a root adbd.
6. No Gradle installed and no `~/.gradle` cache — the project must ship/use the Gradle wrapper
   (first build will download the distribution).
7. `pktmon` needs an elevated shell; run captures from an admin terminal if packet-level evidence is needed.

---

## 10. Skills applied (for the project skill tally)

| skill | path | artifact / decision it changed |
|---|---|---|
| `powershell-windows` | `F:\PyApps\agent-skills\.agent\skills\powershell-windows\SKILL.md` | `scripts\env-boot-wait.ps1`, `scripts\env-launch-electron.ps1`: ASCII-only output markers (`[OK]`/`[i]`/`[X]`), parenthesized cmdlets under logical operators, `ConvertTo-Json -Depth 8/10`, null-checked property access; also forced the `${name}:` delimited interpolation that fixed a real parse error in the cleanup loop. |
| `systematic-debugging` | `F:\PyApps\agent-skills\.agent\skills\systematic-debugging\SKILL.md` | Diagnosed `adb reverse` by evidence collection at each boundary (mapping registered → adbd listener present → device socket established → host listener accepts → 0 payload) instead of guessing; result recorded as the `network.adb_reverse` / `network.adb_forward` fields in `EVIDENCE\env\environment.json`, and the decision to standardise on `10.0.2.2`. |
| `testing-patterns` | `F:\PyApps\agent-skills\.agent\skills\testing-patterns\SKILL.md` | `test-fixtures\fixture-server.js` + `test-fixtures\electron-diff.js`: data-factory pattern (`makeBytes`/`seedFor` per fixture name with a fixed seed), one factory-produced reference per scenario, isolated per-scenario workspace directories, data-driven scenario specs (`runScenario(spec)`) instead of duplicated test code, and reference hashes asserted by exact equality. |
| `android_ui_verification` | `F:\PyApps\agent-skills\.agent\skills\android_ui_verification\SKILL.md` | Device calibration + state discovery workflow applied to establish the UI baseline: `wm size` (1080x2400) → `uiautomator dump`/`adb pull` → `screencap` → focused-window check. Artifacts: `EVIDENCE\screenshots\emulator-boot.png` (Pixel launcher home screen) and `EVIDENCE\screenshots\emulator-home-ui.xml`; confirms the emulator renders and `uiautomator` works for later app verification. |
| `lint-and-validate` | `F:\PyApps\agent-skills\.agent\skills\lint-and-validate\SKILL.md` | Ran read-only validators on everything I produced instead of assuming success: `node --check` on both harness scripts (exit 0), dynamic `import()` of the ESM shim (exports `Downloader,isDebridCDN`), `[Parser]::ParseFile` on both `.ps1` scripts (0 errors), `ConvertFrom-Json` on all 5 emitted JSON files (all valid). Recorded in `EVIDENCE\electron\run-log.txt`. |
| `protocol-reverse-engineering` | `F:\PyApps\agent-skills\.agent\skills\protocol-reverse-engineering\SKILL.md` | `scripts\pcap_parse.py` + `EVIDENCE\captures\CAPTURE-MANIFEST.json`: applied the capture→decode→document→validate workflow to the guest packet capture (tool/binary SHA-256, linktype, packet counts, DNS/SNI extraction); validated by replaying known traffic and asserting the `check.torproject.org` connection appears by TLS SNI and DNS. |

---

## 11. Guest packet capture (tcpdump inside the emulator)

Host `pktmon` is blocked without elevation (§7), so the capture was taken **inside the guest** with
root. Both helper binaries are static and run natively on Android x86_64:

| tool | device path | version | sha256 |
|---|---|---|---|
| tcpdump | `/data/local/tmp/tcpdump` | `tcpdump 4.9.2 / libpcap 1.9.0-PRE-GIT (TPACKET_V3)` | `126636e370e5fdfd3645e2c9458da520f982d360c92b389d7aea13c1ce493bcf` |
| curl | `/data/local/tmp/curl` | `curl 8.23.0 (x86_64-pc-linux-gnu) OpenSSL/4.0.3` | `715f85aae6d9caeca203f7c1f8697b02193c53f8dd6112d54647f0777290f23e` |

Sources: tcpdump from `fkie-cad/friTap` (`tcpdump_x86_64_android`), curl from `stunnel/static-curl`
(`curl-linux-x86_64-musl-8.23.0-1`). Copies kept in `EVIDENCE\captures\tools\`.

```powershell
adb push tcpdump /data/local/tmp/tcpdump ; adb shell "su 0 chmod 755 /data/local/tmp/tcpdump"
adb shell "su 0 /data/local/tmp/tcpdump -i any -n -s 0 -U -w /data/local/tmp/cap.pcap"   # blocks
# ... generate traffic ...
adb shell "su 0 kill -INT $(adb shell 'su 0 toybox pidof tcpdump')"
adb pull /data/local/tmp/cap.pcap EVIDENCE\captures\guest-traffic-2026-10-26.pcap
```

Result: **3129 packets captured / 3227 received by filter / 0 dropped**, linktype **LINUX_SLL (113)**,
2 423 924 bytes, sha256 `d7fdb7fea830706d50ad0b793c7d7e49b1dc797efe79ad8ca40b5aadfde11bec`.
Traffic generated: HTTPS `GET https://check.torproject.org/` → **HTTP 200** (TLS appconnect 0.69 s,
static curl with `--resolve 116.202.120.181`), DNS lookups for `check.torproject.org` /
`example.org` / `torproject.org`, and `GET http://10.0.2.2:8787/health` → 200 plus a
`Range: bytes=0-1048575` download → **206, 1 048 576 bytes**.

Gotcha: static musl curl does **not** use Android's resolver (no `/etc/resolv.conf`), so plain
`curl https://host` fails with `Could not resolve host`; use `--resolve <host>:<port>:<ip>`
(DNS is still exercised by `ping`/bionic, which is visible in the capture).

Parser: `scripts/pcap_parse.py` (stdlib only, no tshark). Handles pcap (both endiannesses,
µs/ns) and pcapng, Ethernet/VLAN, Linux SLL v1 **and** SLL2, DLT_RAW, IPv4/IPv6 (+extension
headers), TCP/UDP, TLS ClientHello **SNI from reassembled TCP streams** (a ClientHello routinely
spans segments), DNS A/AAAA answers, and HTTP first lines.

```powershell
python scripts\pcap_parse.py EVIDENCE\captures\guest-traffic-2026-10-26.pcap
# -> EVIDENCE\captures\guest-traffic-2026-10-26.flows.json
```

Measured: 3125 decoded packets, 4 non-IP frames, 189 flows, 104 destinations.
**`check.torproject.org` is proven present** by TLS SNI → `116.202.120.181:443` (reassembled stream)
and by DNS A/AAAA/HTTPS-RR queries. Evidence manifest with all hashes:
`EVIDENCE\captures\CAPTURE-MANIFEST.json`.

## 12. Tor relay oracle (DEPRECATED — superseded by the allowlist checker in section 15)

`scripts/verify_egress.py` classifies observed endpoints as
`tor-relay | tor-dir | loopback | fixture | DNS | UNKNOWN` against the current running-relay list
from `https://onionoo.torproject.org/details?type=relay&running=true&fields=nickname,or_addresses,exit_addresses`.
The raw payload is cached under `EVIDENCE\captures\onionoo-relays-*.json` with SHA-256 + fetch time
in `onionoo-relays.meta.json` (6 h TTL). Sources: `electron-connections`, `pcap-flows`, `proc-net`
(saved `/proc/net/tcp` dump), or a literal `ip:port` list. Exit code 2 = non-Tor/DNS egress found.

Fetched list: **9164 running relays, 9722 unique OR IPs**, sha256
`e86cb43d986f095e549b9306f823b3964d0fa48341aabcfca8ae34658a9b487f`.

| run | result |
|---|---|
| `--source electron-connections` (`EVIDENCE\electron\app-connections.json`) | `94.140.4.10:443`, `81.85.62.11:443`, `104.20.28.56:443` → **UNKNOWN (non-Tor)**; `127.0.0.1:8191` → loopback. **VERDICT: NON-TOR EGRESS DETECTED** (exit 2) — the original app is *not* Tor-routed. |
| `--source pcap-flows` (guest capture) | 12 external UNKNOWN (check.torproject.org `116.202.120.181:443`, Google/`gstatic` endpoints on 443/80/5228, `93.184.215.14:443`), `10.0.2.3:53` → **DNS** (flagged, never Tor), `10.0.2.2:8787` → fixture. **VERDICT: NON-TOR EGRESS DETECTED** (exit 2) — expected for the stock emulator. |
| positive control (`--source endpoints`) | real relay `91.66.247.27:9006` → **tor-relay [BaumiMiddleRelays]**; `10.0.2.3:53` → DNS; `10.0.2.2:8787` → fixture; `127.0.0.1:8191` → loopback; `94.140.4.10:443` → UNKNOWN. Proves the oracle detects Tor *and* non-Tor, i.e. it is not a rubber stamp. |

Reports: `EVIDENCE\captures\egress-electron.json`, `egress-guest-pcap.json`, `egress-control.json`.

## 13. Fixture server status + emulator storage

- Fixture server: **UP** on `127.0.0.1:8787` (detached host process; stdout in
  `EVIDENCE\env\fixture-server.out.log`); `/health` → **HTTP 200** (1203 bytes); from the device
  `http://10.0.2.2:8787/health` → **200**.
- Restart command (binds `127.0.0.1`; the device reaches it via `10.0.2.2`):
  ```powershell
  Start-Process -FilePath 'node.exe' -PassThru -WindowStyle Hidden -WorkingDirectory 'F:\PyApps\ddandroid\test-fixtures' `
    -ArgumentList @('F:\PyApps\ddandroid\test-fixtures\fixture-server.js','--port','8787',
                    '--log','F:\PyApps\ddandroid\EVIDENCE\env\fixture-requests-standalone.jsonl') `
    -RedirectStandardOutput 'F:\PyApps\ddandroid\EVIDENCE\env\fixture-server.out.log' `
    -RedirectStandardError  'F:\PyApps\ddandroid\EVIDENCE\env\fixture-server.err.log'
  ```
- Emulator `/data`: **12 G total, 1.0 G used, 11 G available (10 %)** — the 8 MiB fixtures fit easily.

## 14. Per-scenario reference values for the Android differential test

From `EVIDENCE\electron\reference-hashes.json` (authoritative) plus the flaky prefix:

| fixture | size | seed | sha256 |
|---|---|---|---|
| `plain.bin` | 8388608 | `0x9b9c5855` | `7ea22e5bbaf0d5711cd6681e5e3054f8fa6a1ed621787d0dc4c74e669fa41c56` |
| `norange.bin` | 8388608 | `0xec087cd5` | `c0db8a124f2728045e183abf438ede13dbc9271ad37d008bb85fef21383b7488` |
| `flaky.bin` | 8388608 | `0xefd79cf4` | `bac64e9df38a6e0a75b398bb04c33e44180e1d3375df32dd2bcad162607213fa` |
| `empty.bin` (declared body) | 1000 | `0x10249464` | `f229154fb9bd64a1cff3487982c00195df4bc6ba34a0729408e00856869f4341` |
| `plain.bin[0:3145728]` | 3145728 | - | `ef30bf0fcda87f299e2a03e4d8199b940fadfcea002dc61b9cd7ec8210c1f70a` |
| `norange.bin[0:3145728]` | 3145728 | - | `752f1cd15b4e8869f843e600c931d1a893ecf8c929e6b4820a2eca42f4fd0027` |
| `flaky.bin[0:4194304]` | 4194304 | - | `f09dce77fdfa7fd34ff46e4e7bed643dfd70fe54fbb4c13eb771c5823ed97295` |

| scenario | request | expected result | assert |
|---|---|---|---|
| s1 fresh | `GET /file/plain.bin`, no Range | 200, 8388608 B | final sha `7ea22e5b…` |
| s2 resume | pre-write 3145728 B (`ef30bf0f…`), `GET` with `Range: bytes=3145728-` | **206** `bytes 3145728-8388607/8388608`, append | final 8388608, sha `7ea22e5b…` |
| s3 range ignored | pre-write 3145728 B of `norange.bin` (`752f1cd1…`), `GET /file/norange.bin` | 200 full body; must NOT append/truncate; report range-ignored | file stays 3145728, sha `752f1cd1…` |
| s4 flaky | `GET /file/flaky.bin` → cut at 4194304; retry with `Range: bytes=4194304-` | 1st: short/aborted (must resolve as failure, not hang); 2nd: **206** from 4194304 | interim sha `f09dce77…`; final 8388608, sha `bac64e9d…` |
| s5 empty | `GET /file/empty.bin` (`Content-Length: 1000`, 0 body) | must resolve as truncation failure (original hung) | 0 bytes written, no success |
| every response | - | header `X-Fixture-Sha256` = full-file reference sha | compare header with table above |

## 15. Egress checker — allowlist mode ("no unexpected egress")

**Scope correction:** "tor" in this project = **TorBox** (the debrid service), not the Tor
network. Relay classification was removed; `scripts/verify_egress.py` v2 is now an allowlist
checker for the real privacy claim: *the client only talks to the APIs it is supposed to, over TLS*.

Default allowlist: `api.torbox.app`, `api.real-debrid.com`, `api.themoviedb.org`,
`image.tmdb.org`, `comet.stremio.ru` (override with `--allowed "a,b"`). Loopback and the debug
fixture `10.0.2.2:8787` are allowed separately.

Each outbound destination is classified from the names actually observed on the wire — TLS SNI
(reassembled ClientHello), HTTP `Host`, DNS answers, and DNS query names for `:53`:
`allowed | loopback | fixture | UNEXPECTED`. A DNS resolver destination is allowed only if **all**
decoded query names are allowlisted. Exit **2** when anything is UNEXPECTED.

```powershell
# direct pcap decode (imports pcap_parse.py) or a saved flows.json
python scripts\verify_egress.py --capture EVIDENCE\captures\app-launch-2026-10-26.pcap --out EVIDENCE\captures\egress-app-launch.json
python scripts\verify_egress.py --flows   EVIDENCE\captures\app-launch-2026-10-26.flows.json
```

Controls (prove it is not a rubber stamp):

| run | result |
|---|---|
| stock-emulator capture, default allowlist | **14 UNEXPECTED** (Google/Gstatic/etc + check.torproject.org) + 1 fixture → `egress-allowlist-control.json`, exit 2 |
| same capture, `--allowed "…,check.torproject.org"` | that endpoint flips to **allowed** (1 allowed, 13 UNEXPECTED) → matching logic works → `egress-allowlist-control2.json` |
| **fresh app-launch capture** (`app-launch-2026-10-26.pcap`, 5054 packets) | **2 UNEXPECTED**: TLS to `www.gstatic.com:443` + its DNS lookup. **No debrid API traffic** — the installed build has no account configured and cannot be scripted (see §16) → `egress-app-launch.json`, exit 2 |

Superseded Tor-oracle artifacts are archived under `EVIDENCE\captures\deprecated-tor-oracle\`.

## 16. Device lifecycle test harness

`scripts/device-lifecycle-test.ps1` (+ JSON under `EVIDENCE\lifecycle\`). Drives the app only
through the debug deep link and adb; never modifies app code. Records every adb command, every
pid/partial-size sample, deviceidle/network state, final size + pulled SHA-256, per-scenario
assertions and a PASS/FAIL table.

Scenarios: **a** process death mid-download (`am force-stop` at ~1.5 s, relaunch, must resume),
**b** Doze (`dumpsys deviceidle force-idle`, 20 s, `unforce`, must finish), **c** network toggle
(`svc wifi/data disable` → 10 s → enable), **d** queue survives `am stopservice` of
`…download.DownloadService` (two downloads), relaunch, both must finish.

Design notes:
- idempotent: each scenario does `am force-stop` + `pm clear` + removes the app's external files
  dir first;
- transfers are slowed with the fixture's `?rate=` knob so a kill/idle/outage actually lands
  mid-transfer (same bytes, same SHA-256);
- blocking preflight gates: adb, device online, boot, **app installed**, **`tddl://download`
  resolvable**, fixture reachable from host *and* device. Non-blocking warnings: DownloadService
  present, app launches;
- exit codes: `0` all requested scenarios PASS, `2` any FAIL, `3` preflight blocked, `1` error.

Current status (2026-10-26): **BLOCKED — the installed APK predates the debug harness hooks.**
`pro.tordownloader.android.debug` v1.0.0 (installed 09:24) has only `MainActivity`
(MAIN/LAUNCHER) — no `tddl://` VIEW intent-filter and no `DownloadService` component; it launches
fine (screenshot `EVIDENCE\screenshots\app-debug-1.0.0.png`; UI text still shows the old
"Every request leaves through the Tor circuit above" copy). Preflight therefore reports:

```
debug_deeplink_resolvable FAIL (raw: "No activities found")
download_service_present  FAIL (component not found: pro.tordownloader.android.debug/pro.tordownloader.android.download.DownloadService)
=> blocked_reason = debug deep link tddl://download is not resolvable in the installed build
```

Run it again once a build with the intent-filter + DownloadService is installed:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File F:\PyApps\ddandroid\scripts\device-lifecycle-test.ps1
# optional: -Only a,c   -Package <id>   -CompleteTimeoutSeconds 180
```

## 17. Fixture throttle + current fixture state

The fixture server accepts an optional `?rate=<bytes/sec>` on `/file/<name>` to stream the body
slowly (paced 64 KiB writes) so lifecycle tests can act mid-transfer. Bytes and SHA-256 are
unchanged; unthrottled requests stay immediate. Verified: `plain.bin?rate=2097152` took 4.42 s
(vs 0.02 s unthrottled) and hashed to `7ea22e5b…41c56`.

Fixture server status: **UP** on `127.0.0.1:8787`; `/health` → 200 (1304 bytes now that it
advertises `throttle_param`); device `http://10.0.2.2:8787/health` → 200. Emulator `/data`:
12 G total, ~1.0 G used, ~11 G free.

## 18. Lifecycle suite on the R8/signed VERIFY build

Supersedes the §16 status note (that one described the stale debug APK).

Build: `F:\PyApps\ddandroid\app\build\outputs\apk\verify\DDownloader-<version>-<code>-verify.apk`, 9 528 564 bytes,
sha256 `340fe00ab6e48873a162b7f723a822792462eb85934f2a8bece8ec42106be5b6`.
Package `pro.tordownloader.android.verify`, versionName 1.0.0, installed with `adb install -r -t`.

Facts established from the installed APK:
- `tddl://` VIEW intent-filter present (scheme `tddl`, host `download` on MainActivity);
  `pro.tordownloader.android.download.DownloadService` declared (verified with
  `aapt2 dump xmltree`, because `dumpsys package` does not enumerate filter-less services).
- **NOT debuggable**: `run-as pro.tordownloader.android.verify id` →
  `run-as: package not debuggable`. The intended `debuggable=true` for the verify type did not take
  effect in this build. Consequence: the harness automatically falls back to **adb-root** for
  partial-file sampling (preflight reports `partial_sampling_access method=root`). If the emulator
  is not rooted, scenario **a**'s partial-size assertion is marked *skipped* rather than failing, and
  final-output verification still works from `/sdcard`.

Harness updates made for this build:
- full parametrisation (`-Serial`, `-Package`, `-Service`, `-Activity`, `-FixtureHost`,
  `-FixturePort`, `-FixtureLog`, `-ApkPath`, `-Aapt2`, `-ToolsDir`, `-Only`, timeouts);
- **explicit component targeting** (`-n <pkg>/<Activity>`): with both the debug and verify builds
  installed, an implicit VIEW intent opens the system chooser (ResolverActivity) and the URL is
  never delivered;
- final-output discovery handles the FUSE view (`/sdcard/Android/data/<pkg>/files`) and the raw
  backing store (`/data/media/0/...`), with a root-read fallback for pulls; `ls -lR` directory
  headers are tracked so device paths are absolute;
- `?rate=` keeps transfers in flight for kills/Doze/toggles; on a fresh emulator the static
  `curl`/`tcpdump` helpers are pushed automatically.

Test isolation: another teammate's `device-differential.ps1` was concurrently using the primary
emulator (`emulator-5554`) and restarting the fixture server on :8787 (its startup truncates the
shared JSONL log — this invalidated a Range assertion once). The suite was therefore run on a
**second headless AVD `dd35b` (emulator-5556, same Pixel 6 / 1080x2400 / API 35 image)** with a
**private fixture server on :8788** (`EVIDENCE\env\fixture-requests-lifecycle.jsonl`). No teammate
processes were killed.

Final run `EVIDENCE\lifecycle\run-20261026T150303Z\lifecycle.json` (JSON + `adb-transcript.log`):

| scenario | result | evidence |
|---|---|---|
| **a** process death mid-download | **PASS** | killed at partial 2 621 440 B (`old_process_terminated`), relaunch resumed with `Range: bytes=2621440-` → **206** `bytes 2621440-8388607/8388608`, final 8 388 608 B sha `7ea22e5b…41c56` = reference |
| **b** Doze (`deviceidle force-idle` 20 s) | **PASS** | `deep=IDLE` during the window, transfer completed, final sha `7ea22e5b…41c56` |
| **c** network toggle (wifi+data off 10 s, on) | **FAIL** | partial frozen at 1 703 936 B through the outage; **no retry request for 180 s** after re-enable; `dumpsys jobscheduler` shows only WorkManager housekeeping jobs; final file never produced |
| **d** queued jobs survive `am stopservice` | **PASS** | service reported `Service stopped`, process stayed alive, both transfers completed: `plain.bin` sha `7ea22e5b…41c56`, `norange.bin` sha `c0db8a12…b7488` |

**Open defect (c):** a transfer that loses the network mid-download does not recover by itself when
the network returns — observed twice (120 s and 180 s windows) with no retry request and no pending
download job. Root cause not established; `NetworkMonitor.kt` / `ResumeWorker.kt` are the likely
places to look. Report only; app code untouched.

## 19. UI goldens + accessibility audit

Goldens (verify build on dd35b, 1080x2400 @420dpi), `EVIDENCE\screenshots\golden\`:
`search.png`, `discover.png`, `downloads.png`, `settings.png`, `logs.png` + matching `<screen>.xml`
uiautomator dumps + `goldens.json`. Tap points were **computed from live uiautomator bounds** (not
the supplied estimate): Search (157,2133), Discover (365,2133), Downloads (572,2133),
Settings (779,2133), Logs (955,2133); each screen's visible text was recorded to prove navigation.

Audit (`scripts/a11y-audit.py` → `EVIDENCE\a11y\a11y-report.json` + `summary.md`), 5 screens,
378 nodes:
- **0 violations** for R1 (clickable accessible name), R2 (>= 48dp touch target), R3 (icon desc).
- 3 clickable nodes on Settings are **clipped-skipped** (partially scrolled out of the LazyColumn
  viewport; uiautomator reports truncated bounds, e.g. `[89,2059][298,2075]` = top 16 px of the
  Real-Debrid Save/Clear buttons). Not defects.
- Two earlier findings were checker artifacts and were fixed: (1) a Compose icon button carries its
  label on a **child** node's `content-desc` (top-bar Logs button) — R1 now counts descendant
  descriptions; (2) clipped nodes — see above.
- R3 icon coverage: 0 image/icon-like nodes detected; Compose renders icons as plain
  `android.view.View`, so R3 is *no coverage*, not a pass.

WCAG contrast (WCAG 2.1 relative luminance, opaque sRGB from `Theme.kt`):

| pair | ratio | verdict |
|---|---|---|
| White on `brandButton` start `#6D4AE0` | **5.67:1** | PASS (AA body) |
| White on `brandButton` end `#4F46E5` | **6.29:1** | PASS (AA body) |
| TextLow `#8590A3` on Ink2/Ink1/Ink0 | 5.76 / 6.07 / 6.28 | PASS |
| TextMid on Ink2/Ink1/Ink0 | 7.48 / 7.87 / 8.16 | PASS |
| Rose/Mint/Cyan/Amber on Ink2 | 6.58 / 9.66 / 10.27 / 11.12 | PASS |
| TextHi on Ink2/Ink1/Ink0 | 16.89 / 17.79 / 18.43 | PASS |
| White on Violet (decorative brand gradient) | 4.35:1 | large-only |
| White on Cyan (decorative brand gradient) | **1.81:1** | below AA even for large text |

The two low ratios are the **decorative** `brand` gradient endpoints (progress rings, icon tints);
`Theme.kt` already defines `brandButton` so text-bearing gradient buttons clear AA (its code comment
cites the 1.81:1 measurement). All text-bearing pairs pass. TextLow was darkened to `#8590A3`
(was `#5F6A7D` at 3.40–3.71:1) — no remaining body-text contrast failure.
