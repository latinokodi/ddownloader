# EVIDENCE/research — raw primary evidence index

Collected 2026-10-26 (America/Bogota) by `evidence-researcher` for `docs/research-tor.md` and `docs/research-stack.md`.
Everything here is a **verbatim fetch or a deterministic parse** of a primary source. Nothing in `raw/` was hand-edited.

## Layout

```
EVIDENCE/research/
├── README.md                 ← this file
├── artifacts/
│   ├── tor-android-0.4.9.14.aar            (12,976,254 B)
│   ├── tor-android-0.4.9.14.aar.elf-scan.json     ← PT_LOAD p_align per ABI
│   ├── tor-android-0.4.9.14.aar.relro-scan.json   ← GNU_RELRO rule check per ABI
│   ├── tor-android-0.4.9.14.aar.relro-layout-arm64-v8a.json
│   └── tor-android-0.4.9.14.aar.relro-layout-x86_64.json
├── scripts/
│   ├── parse_elf_palign.py      ← ELF64/ELF32 PT_LOAD p_align + congruence (stdlib only)
│   ├── check_relro.py           ← full 16 KB rule incl. "RELRO is its own LOAD" exception
│   └── inspect_relro_layout.py  ← section-level layout around RELRO end page
└── raw/                         ← 82 fetched files (Maven metadata, POMs, READMEs, CHANGELOGs,
                                    GitHub/GitLab API JSON, Android docs snapshots)
```

## Reproduce the decisive 16 KB result

```powershell
cd F:\PyApps\ddandroid\EVIDENCE\research
# 1. fetch (any mirror works; this is the exact URL used)
Invoke-WebRequest 'https://repo1.maven.org/maven2/info/guardianproject/tor-android/0.4.9.14/tor-android-0.4.9.14.aar' -OutFile artifacts\tor-android-0.4.9.14.aar
# 2. verify identity
(Get-FileHash artifacts\tor-android-0.4.9.14.aar -Algorithm SHA256).Hash
#   expected: 28FF2BB6D4D42EABE57183DEDD7A97FCE77E8CE4B6F5AF6FA03993B3CA054EDD
# 3. parse
python scripts\parse_elf_palign.py artifacts\tor-android-0.4.9.14.aar
python scripts\check_relro.py      artifacts\tor-android-0.4.9.14.aar
```

Measured result (2026-10-26):

| ABI | ELF | `libtor.so` bytes | sha256 (first 8) | PT_LOAD | min `p_align` | 16 KB |
|---|---|---|---|---|---|---|
| arm64-v8a | 64 | 7,580,256 | 67ce86a6 | 3 | **0x4000** | PASS |
| x86_64 | 64 | 8,445,824 | dc7f0fec | 3 | **0x4000** | PASS |
| armeabi-v7a | 32 | 6,819,684 | a27f80fc | 3 | 0x1000 | n/a (32-bit) |
| x86 | 32 | 7,229,832 | 982904d2 | 3 | 0x1000 | n/a (32-bit) |

Cross-checked against the documented rule at `https://developer.android.com/guide/practices/page-sizes`
(the `GNU_RELRO` "own LOAD + next writable LOAD on a separate 16 KB page" exception applies for both 64-bit ABIs;
`inspect_relro_layout.py` shows `.relro_padding` ends the RELRO LOAD and `.data` begins in the next 16 KB page).

## Claim → evidence map (selected)

| Claim in the docs | Evidence file(s) |
|---|---|
| tor-android 0.4.9.14 is latest on Maven Central, 2026-10-09 | `raw/maven-central-tor-android-maven-metadata.xml`, `raw/maven-central-tor-android-listing.html`, `raw/maven-central-tor-android-versions.txt` |
| README pins 0.4.9.13.2 + jtorctl 0.4.5.7; bundled tor 0.4.9.14 | `raw/tor-android-README.md` |
| README's gpmaven mirror is stale (0.4.8.16, 2025-05-16) | `raw/gpmaven-tor-android-maven-metadata.xml` |
| BSD-3 licensing | `raw/maven-central-tor-android-0.4.9.14.pom`, `raw/tor-android-LICENSE.txt`, `raw/orbot-LICENSE.txt` |
| Transitive deps (localbroadcastmanager 1.1.0, jtorctl 0.4.5.7, kotlin-stdlib 2.2.10) | `raw/maven-central-tor-android-0.4.9.14.pom` |
| Tor 0.4.9.14 = high-severity security release (2026-10-08) | session fetch of `https://forum.torproject.org/t/security-release-0-4-9-14/22241` (quoted in doc; forum HTML is not retained) |
| Release cadence, 16 KB NDK fix, open crash cluster | `raw/ghapi-tor-android-repo.json`, `raw/ghapi-tor-android-open-issues.json`, `raw/ghapi-tor-android-issue-170.json`, `raw/ghapi-tor-android-issue-184.json`, `raw/tor-android-CHANGELOG.md` |
| TorService start/stop/status contract + dynamic ports | `raw/tor-android-TorService.java`, `raw/ghapi-tor-android-tree.json` |
| Arti status, Android guide staleness, abandoned sample, mobile issues | `raw/arti-README.md`, `raw/arti-doc-Android.md`, `raw/gitlab-arti-commits-Android-doc.json`, `raw/gitlab-arti-mobile-example.json`, `raw/gitlab-arti-issues-mobile.json`, `raw/cratesio-arti-client.json`, `raw/cratesio-arti.json` |
| Orbot latest release, manifest, constants, intent API | `raw/ghapi-orbot-releases.json`, `raw/orbot-AndroidManifest.xml`, `raw/orbot-OrbotConstants.kt`, `raw/orbot-README.md`, `raw/netcipher-OrbotHelper.java` |
| OkHttp SOCKS ⇒ remote DNS (`createUnresolved`) | `raw/okhttp-RouteSelector.kt` |
| Stack versions/dates | `raw/npm-*.json`, `raw/pubdev-*.json`, `raw/flutter-releases-windows.json`, `raw/mavenmeta-*.xml`, `raw/maven-kotlin-stdlib-2.4.21.pom` |
| Flutter BSD-3 | `raw/flutter-LICENSE.txt` |
| Capacitor background limits + no proxy option | session fetches of `capacitorjs.com/docs/apis/background-runner` and `/apis/http` (quoted in doc; HTML not retained) |
| Android 15 `dataSync` 6 h + `onTimeout`; WorkManager persistence; 16 KB Play rule | session fetches of `developer.android.com/about/versions/15/behavior-changes-15`, `…/background-tasks/persistent`, `…/guide/practices/page-sizes` (quoted in doc; HTML not retained) |
| Background-download failure reports | `raw/ghapi-search-repo_3Afluttercommun.json` (#982, #959), `raw/ghapi-flutter_downloader-repo.json` (351 open issues), `raw/ghapi-kesha-rnbgdl-repo.json`, `raw/ghapi-rntor-repo.json` |

## Retention gaps (honest)

Four critical pages were read through a text-proxy renderer (`r.jina.ai`) or read directly and quoted, but their HTML was **not** saved to `raw/`: the Tor 0.4.9.14 forum post, the Android 15 behavior-changes page, the 16 KB page-sizes page, and the two Capacitor API pages. The quotes in the docs are verbatim from those fetches (2026-10-26); re-fetch with `r.jina.ai/<url>` to reproduce, since Google/Medium-family pages truncate when fetched raw.
