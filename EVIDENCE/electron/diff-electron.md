# Electron Downloader — differential ground truth

Generated: 2026-10-26T14:09:37.415Z  |  by: `env-runner :: test-fixtures/electron-diff.js`

Ground truth source: `F:\PyApps\tordownloaderElectron (Electron 42.11.10 app, version 1.0.18); compiled Downloader at dist-electron/electron/downloader.js`

## Modules

| module | path | sha256 |
|---|---|---|
| shipped (byte-identical copy) | `F:\PyApps\ddandroid\EVIDENCE\electron\shim\downloader.original.js` | `0e6fce3ed554b6e3b536ccb9180f67e51fea537dccfaf65c4ec35b36ebf731bc` |
| TEST-ONLY shim | `F:\PyApps\ddandroid\EVIDENCE\electron\shim\downloader.test.js` | `326f742722749fa32bf08695457eef4b93bfa9ce1a64172084aedf5aef3e63ef` |

Shim delta: ONLY the debrid allowlist constants were extended with '127.0.0.1' and 'localhost'; all other bytes identical to the original

## Allowlist guard probe (proves the shim is the only difference)

| host | original isDebridCDN | shim isDebridCDN |
|---|---|---|
| 127.0.0.1 | false | true |
| localhost | false | true |
| dl.torbox.app | true | true |
| cdn.torbox.app | true | true |
| torbox.app | true | true |
| media.torbox.app | true | true |
| tb-cdn.io | true | true |
| tb-cdn.cx | true | true |
| download.real-debrid.com | true | true |
| rd.nu | true | true |
| evil.example.com | false | false |
| not-torbox.app.evil.com | false | false |

Original `new Downloader(`http://127.0.0.1:9/...`)` -> {"threw":true,"message":"Security: Download rejected. URL http://127.0.0.1:9/file/plain.bin is not a recognized debrid CDN domain. This app only downloads via debrid services (TorBox / Real-Debrid) — never via torrents/seeds/P2P."}

## Reference streams (fixture server, deterministic xorshift32)

PRNG: xorshift32 seeded by (0x1234abcd ^ fnv1a32(name))

| file | size | seed | sha256 (reference) |
|---|---|---|---|
| plain.bin | 8388608 | 0x9b9c5855 | `7ea22e5bbaf0d5711cd6681e5e3054f8fa6a1ed621787d0dc4c74e669fa41c56` |
| norange.bin | 8388608 | 0xec087cd5 | `c0db8a124f2728045e183abf438ede13dbc9271ad37d008bb85fef21383b7488` |
| flaky.bin | 8388608 | 0xefd79cf4 | `bac64e9df38a6e0a75b398bb04c33e44180e1d3375df32dd2bcad162607213fa` |
| empty.bin | 1000 | 0x10249464 | `f229154fb9bd64a1cff3487982c00195df4bc6ba34a0729408e00856869f4341` |

Partial prefix (3145728 bytes) hashes:

| prefix | sha256 |
|---|---|
| plain.bin@3145728 | `ef30bf0fcda87f299e2a03e4d8199b940fadfcea002dc61b9cd7ec8210c1f70a` |
| norange.bin@3145728 | `752f1cd15b4e8869f843e600c931d1a893ecf8c929e6b4820a2eca42f4fd0027` |

## Scenario results

### s1-fresh-full

fresh full download of plain.bin (server supports Range, no partial present)

| attempt | timed out | Downloader result | final size | sha256 | matches reference |
|---|---|---|---|---|---|
| 1 | false | `{"success":true,"bytes_downloaded":8388608}` | 8388608 | `7ea22e5bbaf0d5711cd6681e5e3054f8fa6a1ed621787d0dc4c74e669fa41c56` | true |

Requests observed by the fixture server:

| # | path | Range header | status | Content-Range | bytes written | cut |
|---|---|---|---|---|---|---|
| 2 | /file/plain.bin | - | 200 | - | 8388608 |  |

Expected reference: size=8388608 sha256=`7ea22e5bbaf0d5711cd6681e5e3054f8fa6a1ed621787d0dc4c74e669fa41c56` (plain.bin)

### s2-resume-range

resume from pre-existing 3145728-byte partial of plain.bin; server honours Range

| attempt | timed out | Downloader result | final size | sha256 | matches reference |
|---|---|---|---|---|---|
| 1 | false | `{"success":true,"bytes_downloaded":8388608}` | 8388608 | `7ea22e5bbaf0d5711cd6681e5e3054f8fa6a1ed621787d0dc4c74e669fa41c56` | true |

Requests observed by the fixture server:

| # | path | Range header | status | Content-Range | bytes written | cut |
|---|---|---|---|---|---|---|
| 3 | /file/plain.bin | bytes=3145728- | 206 | bytes 3145728-8388607/8388608 | 5242880 |  |

Expected reference: size=8388608 sha256=`7ea22e5bbaf0d5711cd6681e5e3054f8fa6a1ed621787d0dc4c74e669fa41c56` (plain.bin)

### s3-norange-ignored

resume attempt against norange.bin (server always returns 200 full body) with 3145728-byte partial

| attempt | timed out | Downloader result | final size | sha256 | matches reference |
|---|---|---|---|---|---|
| 1 | false | `{"success":false,"error":"Range not supported by CDN","bytes_downloaded":3145728,"rangeIgnored":true}` | 3145728 | `752f1cd15b4e8869f843e600c931d1a893ecf8c929e6b4820a2eca42f4fd0027` | false |

Requests observed by the fixture server:

| # | path | Range header | status | Content-Range | bytes written | cut |
|---|---|---|---|---|---|---|
| 4 | /file/norange.bin | bytes=3145728- | 200 | - | 8388608 |  |

Expected reference: size=8388608 sha256=`c0db8a124f2728045e183abf438ede13dbc9271ad37d008bb85fef21383b7488` (norange.bin)

### s4-flaky-cut

flaky.bin: server cuts the first response after 4194304 bytes; a retry must resume via Range

| attempt | timed out | Downloader result | final size | sha256 | matches reference |
|---|---|---|---|---|---|
| 1 | true | `null` | 4194304 | `f09dce77fdfa7fd34ff46e4e7bed643dfd70fe54fbb4c13eb771c5823ed97295` | false |
| 2 | false | `{"success":true,"bytes_downloaded":8388608}` | 8388608 | `bac64e9df38a6e0a75b398bb04c33e44180e1d3375df32dd2bcad162607213fa` | true |

Requests observed by the fixture server:

| # | path | Range header | status | Content-Range | bytes written | cut |
|---|---|---|---|---|---|---|
| 5 | /file/flaky.bin | - | 200 | - | 4194304 | YES |
| 6 | /file/flaky.bin | bytes=4194304- | 206 | bytes 4194304-8388607/8388608 | 4194304 |  |

Expected reference: size=8388608 sha256=`bac64e9df38a6e0a75b398bb04c33e44180e1d3375df32dd2bcad162607213fa` (flaky.bin)

### s5-empty-truncation-extra

EXTRA: empty.bin advertises Content-Length 1000 but sends 0 body bytes -> truncation must be detected

| attempt | timed out | Downloader result | final size | sha256 | matches reference |
|---|---|---|---|---|---|
| 1 | true | `null` | 0 | `null` | false |

Requests observed by the fixture server:

| # | path | Range header | status | Content-Range | bytes written | cut |
|---|---|---|---|---|---|---|
| 7 | /file/empty.bin | - | 200 | - | 0 |  |

Expected reference: size=1000 sha256=`f229154fb9bd64a1cff3487982c00195df4bc6ba34a0729408e00856869f4341` (empty.bin)

## Exact reproduction commands

```powershell
# 1. copy the compiled ground-truth downloader and create the ESM shim
Copy-Item "F:\PyApps\tordownloaderElectron\dist-electron\electron\downloader.js" "F:\PyApps\ddandroid\EVIDENCE\electron\shim\downloader.original.js"
Copy-Item "F:\PyApps\ddandroid\EVIDENCE\electron\shim\downloader.original.js" "F:\PyApps\ddandroid\EVIDENCE\electron\shim\downloader.test.js"
# edit shim: extend REALDEBRID_CDN_DOMAINS with '127.0.0.1','localhost'
cmd /c mklink /J "F:\PyApps\ddandroid\EVIDENCE\electron\shim\node_modules" "F:\PyApps\tordownloaderElectron\node_modules"
# 2. run the harness
cd F:\PyApps\ddandroid\test-fixtures; node electron-diff.js
# 3. standalone fixture server (for the Android side)
cd F:\PyApps\ddandroid\test-fixtures; node fixture-server.js --port 8787 --log fixture-requests.jsonl
```
