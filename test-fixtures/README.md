# Deterministic download fixtures (shared by Electron ground truth and the Android port)

Zero-dependency Node HTTP server that serves **byte-identical, reproducible** content so the
Electron original and the Android port can be compared by SHA-256 alone. No network, no npm install.

```
F:\PyApps\ddandroid\test-fixtures\
  fixture-server.js     the server (+ exportable module API)
  electron-diff.js      differential harness for the real Electron Downloader
  README.md             this file
```

## Run

```powershell
cd F:\PyApps\ddandroid\test-fixtures
# standalone server for the Android side (writes a JSONL request log)
node fixture-server.js --port 8787 --log fixture-requests.jsonl
# differential harness (starts its own server on an ephemeral port)
node electron-diff.js
```

Options: `--port <n>` (default 8787), `--log <path>` (default `./fixture-requests.jsonl`), `--no-log`.
The server binds `127.0.0.1` only.

### Throttle option (for lifecycle tests)

Append `?rate=<bytes/sec>` to any `/file/<name>` request to stream the body slowly (paced 64 KiB
writes). The bytes and the final SHA-256 are **identical**; only timing changes. This lets a
lifecycle test kill the app / force Doze / cut the network while a transfer is genuinely in
flight.

```
GET /file/plain.bin?rate=2097152      # 8 MiB at ~2 MiB/s => ~4.4 s
```

Verified: `plain.bin` unthrottled 0.02 s; `?rate=2097152` 4.42 s; both hash to
`7ea22e5b…41c56`. The response also carries `X-Fixture-Rate: <rate>` when throttled, and `/health`
reports `throttle_param`.

### Calling it from the Android emulator

`adb reverse` does **not** work on this host (see `docs/ENVIRONMENT.md` §5). Use the emulator's
host-loopback alias:

```
http://10.0.2.2:8787/file/plain.bin
```

Verified from the device shell: `GET /health` → 200, and `Range: bytes=0-31` → 206
`Content-Range: bytes 0-31/8388608`.

## Deterministic byte stream (must be reimplemented identically in Kotlin)

```
BASE_SEED = 0x1234abcd

fnv1a32(s):
    h = 2166136261
    for each byte b of s:  h = ((h ^ b) * 16777619) & 0xFFFFFFFF
    return h

seed(name) = (BASE_SEED ^ fnv1a32(name)) & 0xFFFFFFFF

x = seed(name)
for i in 0..length-1:
    x ^= (x << 13) & 0xFFFFFFFF
    x ^= x >>> 17
    x ^= (x << 5)  & 0xFFFFFFFF
    byte[i] = x & 0xFF
```

In Kotlin/Java hold `x` as `Int` and use `xor`, `shl`, `ushr`; the low 8 bits are identical to the
unsigned-32 arithmetic above (`byte = (x and 0xFF).toByte()`).

## Reference SHA-256 (do not need the server to compare)

| file | size | seed | sha256 |
|---|---|---|---|
| `plain.bin` | 8388608 | 0x9b9c5855 | `7ea22e5bbaf0d5711cd6681e5e3054f8fa6a1ed621787d0dc4c74e669fa41c56` |
| `norange.bin` | 8388608 | 0xec087cd5 | `c0db8a124f2728045e183abf438ede13dbc9271ad37d008bb85fef21383b7488` |
| `flaky.bin` | 8388608 | 0xefd79cf4 | `bac64e9df38a6e0a75b398bb04c33e44180e1d3375df32dd2bcad162607213fa` |
| `empty.bin` | 1000 | 0x10249464 | `f229154fb9bd64a1cff3487982c00195df4bc6ba34a0729408e00856869f4341` |
| `plain.bin[0:3145728]` | 3145728 | - | `ef30bf0fcda87f299e2a03e4d8199b940fadfcea002dc61b9cd7ec8210c1f70a` |
| `norange.bin[0:3145728]` | 3145728 | - | `752f1cd15b4e8869f843e600c931d1a893ecf8c929e6b4820a2eca42f4fd0027` |

`EVIDENCE\electron\reference-hashes.json` is the authoritative machine-readable copy
(it also carries the seed for each file).

## Endpoints

| endpoint | behaviour |
|---|---|
| `GET /health` | JSON metadata: sizes, seeds, per-file reference sha256, partial size, flaky cut size |
| `GET /file/plain.bin` | 200 full body (8388608 B). Honours `Range: bytes=start-[end]` → 206 + `Content-Range` + `Accept-Ranges: bytes` |
| `GET /file/norange.bin` | Always **200** full body, ignores `Range` (`Accept-Ranges: none`) — tests "server ignored Range" |
| `GET /file/flaky.bin` | Honours Range, but the **first** request delivers exactly 4194304 bytes and then destroys the socket (`cut: true` in the log). Later requests are normal — tests resume-after-cut |
| `GET /file/empty.bin` | `200` + `Content-Length: 1000` but **0 body bytes**, then ends — tests truncation detection |
| anything else | 404 (unknown `/file/<name>`, non-GET → 405) |

Responses also carry `X-Fixture-Name` and `X-Fixture-Sha256` so a client can detect a corrupt body
without out-of-band data.

## Request log

Every request is appended as one JSON object per line:

```json
{"seq":3,"ts":"...","method":"GET","path":"/file/plain.bin","raw_url":"/file/plain.bin",
 "range_header":"bytes=3145728-","status":206,"content_range":"bytes 3145728-8388607/8388608",
 "content_length_header":5242880,"bytes_written":5242880,"cut":false,
 "remote_port":62082,"user_agent":null,"duration_ms":15}
```

`bytes_written` is the number of application-level bytes handed to the socket (not a kernel
accounting); `cut` marks the intentional flaky truncation.

## Module API (used by `electron-diff.js`)

```js
const F = require('./fixture-server.js');
F.createServer({ logPath, onRequest, quiet })   // http.Server with .fixture = { records, reset, health }
F.makeBytes(name, length)                        // deterministic Buffer
F.seedFor(name)                                  // uint32 seed
F.SIZE, F.PARTIAL_SIZE, F.FLAKY_CUT, F.EMPTY_DECLARED, F.CONTENT, F.FILES, F.health()
```

## Electron ground-truth result (see `EVIDENCE/electron/diff-electron.json`)

1. fresh full download → 8388608 B, exact SHA match, `{success:true}`.
2. resume from 3145728 B → server `206`, append, 8388608 B, exact SHA match.
3. server ignores Range → `{success:false, rangeIgnored:true, bytes_downloaded:3145728}`, partial kept.
4. flaky cut at 4194304 B → **first `start()` never resolves** (no `DownloadResult`); retry → `206` from 4194304 → exact SHA match.
5. (extra) `empty.bin` 0-byte body → **`start()` never resolves**.

The Android port should be compared against these with the same reference hashes.
