# Source Analysis — TorDownloader PRO (Electron) — Android Port Ground Truth

| Field | Value |
|---|---|
| Subject | `F:\PyApps\tordownloaderElectron` — TorDownloader PRO v1.0.18 |
| Analyzed snapshot | Working tree on branch `main`, base commit `1f70fd4af400088e39ed370e896f6a02046fdf06` (2026-08-27, "fix: robust season downloads, resume/retry, cache, detection; bump to 1.0.18") **plus 13 uncommitted modified files** (listed in §0.3). `electron/main.ts` and `electron/preload.ts` were rewritten at 2026-10-26 09:04:35/09:04:42, i.e. while this analysis was running. |
| Evidence convention | Every claim cites `path:line` or a line range in the repo. Line numbers refer to the working-tree files with the SHA-256 prefixes recorded in §0.3. |
| Method | Full read of all 14 hand-written TypeScript modules in `electron/`, all 6 Python files there (`qbit-runner.py`, `helpers.py`, `novaprinter.py`, `tmdb-provider.py`, `latino-providers.py`, `subtitles.py`), all 16 renderer components, both locale files, stores, hooks, type contract, build config, and both `src/__tests__` suites. Binaries, the 23 engine plugins (summarized by name) and vendored FlareSolverr/Chromium trees excluded (see §1.6). |
| Authority | This document describes **what the code does**, not what the README claims. Where the two disagree it is flagged in §9. |
| Critical framing | "tordownloader" = **TorBox debrid**, not the Tor network. The app never speaks BitTorrent: `electron/downloader.ts:60-67` refuses any URL that is not a debrid CDN, and TorBox is asked to never seed (`seed=0`, `electron/torbox.ts:93`). The Lead's added Tor routing is **new scope**, not parity. |

## 0. Executive summary for the port

1. **Architecture:** Electron main process (Node) + React renderer behind a `contextBridge` whitelist + a 10-second polling worker + four PyInstaller sidecars (`qbit-runner`, `tmdb-provider`, `latino-providers`, `subtitles`) + a bundled FlareSolverr + a hidden `BrowserWindow` used as a Cloudflare fetch proxy. Media is fetched from debrid CDN URLs and written to arbitrary Windows paths (§1, §4, §5).
2. **Persistence is one SQLite file** created with `better-sqlite3` in WAL mode at `%APPDATA%\tordownloader-pro\tordownloader.db` (`electron/db.ts:5,10`; path forced in `electron/main.ts:24-25`). Two tables: `settings` (1 row, id=1) and `downloads`. **Tokens are stored in plaintext** — no encryption, no OS keychain (§3, §6.3).
3. **Cloud tracking is by `torbox_id` only.** The worker polls the debrid account every 10 s, but it only updates rows that already exist locally; torrents added outside the app are ignored (`electron/worker.ts:145-166,185-186`).
4. **Local download engine** = resume-capable single-stream HTTP downloader with CDN allowlist, 3 link attempts × 3 outer attempts, truncated-stream detection, `416` treated as success, `200`-instead-of-`206` handled as `rangeIgnored`, then size verification with the "accept on attempt ≥ 2" rule (§5.2-§5.4).
5. **Concurrency:** max **3** simultaneous local downloads, FIFO queue (`electron/worker.ts:20,26-55`). Season packs are added as N separate cloud transfers, each downloading its own files.
6. **Jellyfin layout is deterministic and tested:** `parseReleaseName` + `computeDestination` produce `<Movies|Series root>/<Title (Year)>` and `<Series root>/<Title (Year)>/Season NN`, then series files are renamed to `<Parsed Title>.SxxEyy.<ext>` (§5.7-§5.8; tests in `src/__tests__/media-layout.test.ts`).
7. **Subtitle pipeline runs before the rename** so sidecars can be matched to the raw filenames, then all sidecars are renamed with the video (`electron/worker.ts:495-502,710-878`).
8. **Discovery has three independent source families:** qBittorrent-plugin metasearch (23 engine files), TMDB (Python sidecar), and latino providers (`TCL`/`Cinecalidad`/`Comet`) plus a hard-coded Comet Stremio capability URL (§4.3, §4.8-§4.9, §4.12).
9. **Cloudflare path is four-deep:** cloudscraper → Electron Chromium bridge (`cf-fetcher.ts` local HTTP server) → FlareSolverr at `localhost:8191` → plain `requests` (`electron/qbit-plugins/helpers.py:187-234`).
10. **Auto-update trusts GitHub Releases only** — `electron-updater` from `latinokodi/tordownloader-pro`, no code signing configured, no signature check; a release asset compromise is remote code execution (§4.7, §6.6).
11. **Nothing in the original is Tor.** No SOCKS proxy, no `tor` binary, no `.onion`. Adding Tor routing on Android is additive; it will change egress for every API/CDN call if applied globally (§7.1).
12. **Biggest portability cliffs:** Python sidecars (4 exes), bundled FlareSolverr + Chrome, arbitrary filesystem paths, `dialog`/`shell.openPath`, `electron-updater`, `better-sqlite3` (§7).

---

## 0.1 Reading paths

- **Build the data layer / worker:** §3 → §5.2 → §5.3 → §5.4 → §5.5.
- **Build the UI:** `docs/ui-inventory.md` (component/token level) and §2 (IPC contract).
- **Build search/discovery:** §4.3 (metasearch), §4.8 (TMDB), §4.9 (latino), §4.4/§4.5 (Cloudflare).
- **Security review before release:** §6 in full, then §9 (open questions).
- **Parity tracking:** `docs/parity-draft.md`.

## 0.2 Glossary (use these names in code)

| Term | Meaning in this codebase |
|---|---|
| debrid | TorBox or Real-Debrid. The only download source. |
| cloud status | TorBox/Real-Debrid transfer state stored in `downloads.status`. |
| local status | App-owned download state stored in `downloads.local_status` (the free-text field, §3.7). |
| transfer | One cloud torrent record; may contain many files. |
| own files | The subset of files a given transfer downloaded, used to scope rename/subtitles (`electron/worker.ts:718-724`). |
| season override | The `downloads.season` value recorded when the transfer was added; wins over the name parser (`electron/media-layout.ts:172-181,219`). |
| latino providers | TCL (hacktorrent.to), Cinecalidad (via Torrentio), Comet (Stremio addon). |
| Comet base | The hard-coded, capability-configured Comet addon URL at `electron/main.ts:1067`. |

## 0.3 Snapshot pinning (re-verify before trusting line numbers)

`git status` at analysis time (13 modified files, +445/−204): `electron/main.ts`, `electron/preload.ts`, `electron/metasearch.ts`, `electron/qbit-plugins/qbit-runner.py`, `electron/qbit-plugins/novaprinter.py`, `electron/qbit-plugins/engines/wolfmax4k.py`, `src/App.tsx`, `src/store/searchTabs.ts`, `src/types/electron.ts`, `src/hooks/useApi.ts`, `src/__tests__/stores/searchTabs.test.ts`, `package-lock.json`, `start.bat`.

SHA-256 (first 16 hex) of files whose line numbers are cited most:

| File | SHA-256 prefix |
|---|---|
| `electron/main.ts` | `60DB267C28F87C1C` |
| `electron/preload.ts` | `E778AA94BB91102F` |
| `electron/worker.ts` | `199F0CFDAB00F498` |
| `electron/db.ts` | `584BFD86D056C6B8` |
| `electron/downloader.ts` | `EABE8F2276EF` |
| `electron/media-layout.ts` | `1D78212A5438` |
| `electron/torbox.ts` | `9B7B0EFC6904` |
| `electron/realdebrid.ts` | `F2B2D3CD0D6C` |
| `electron/metasearch.ts` | `3B37AA35B66E` |
| `src/App.tsx` | `254150416BAD` (recomputed after the 09:04 edit) |

> **Flag:** `electron/main.ts` + `electron/preload.ts` were modified during this analysis (the change adds `tabId` routing to search events and exposes `cometManifest`/`cometStreams`; see §9.1). If a build is produced from a different revision, re-run `git diff` before trusting the IPC table. Content-level facts are stable; line numbers are the fragile part.

---

## 1. Module map

### 1.1 Process model

| Process | Entry | Lifetime | Responsibility |
|---|---|---|---|
| Electron main | `electron/main.ts` | app lifetime | Windows, IPC, DB init, Python spawns, updater, FlareSolverr/CF bridge supervision |
| Preload | `electron/preload.ts` | per window | `contextBridge.exposeInMainWorld('electronAPI', api)` — 47 invoke wrappers + 11 event subscriptions = 58 exported functions (`electron/preload.ts:121`) |
| Renderer | `src/main.tsx` → `src/App.tsx` | per window | React 18 SPA, Tailwind, Zustand stores, i18n |
| Worker | `setInterval` inside main | app lifetime | 10 s debrid poll + local download queue (`electron/worker.ts:145-166`) |
| Python sidecars | 4 scripts / PyInstaller exes | per invocation | metasearch, TMDB, latino providers, subtitles (§4.3, §4.8-§4.10) |
| FlareSolverr | `flaresolverr.exe` | app lifetime (restarts ≤3) | Cloudflare proxy on port 8191 (`electron/flaresolverr.ts:18-21`) |
| Hidden Chromium | `BrowserWindow` per fetch | per fetch | CF bridge target (`electron/cf-fetcher.ts:34-127`) |

Window: 1280×860, min 900×600, background `#0a0a0a`, title `TORDOWNLOADER` (`electron/main.ts:80-94`). Single-instance lock with explicit `userData` path so it cannot collide with the sibling "serv" build (`electron/main.ts:17-36`).

### 1.2 `electron/*.ts` — hand-written main-process modules

| File | Lines | Responsibility | Key exports | Depends on |
|---|---|---|---|---|
| `main.ts` | 1193 | App bootstrap, all 47 `ipcMain.handle` channels, log collector, Python spawn helpers, Comet/catalog fetchers, updater/FlareSolverr wiring | (side-effect module; `CometStream` type) | every other electron module |
| `preload.ts` | 121 | Renderer whitelist; wraps every channel; event subscriptions return unsubscribe fns | `electronAPI` (global) | electron `contextBridge`/`ipcRenderer` |
| `db.ts` | 172 | SQLite open/migrate; typed CRUD; `Settings`/`Download` interfaces | `initDB`, `getSettings`, `updateSettings`, `getDownloads`, `getDownloadByTorboxId`, `addDownload`, `updateDownload`, `deleteDownload`, `getDB`, types | `better-sqlite3`, `electron.app` |
| `torbox.ts` | 212 | TorBox API client + normalizers | `TorboxAPI` (7 methods, 3 statics) | axios |
| `realdebrid.ts` | 275 | Real-Debrid OAuth + REST client + normalizers | `RealDebridAPI`, `RD_OPENSOURCE_CLIENT_ID` | axios |
| `downloader.ts` | 193 | Single-file resumable HTTP downloader with CDN hard guard | `Downloader`, `isDebridCDN`, `DownloadProgress`, `DownloadResult` | axios, fs |
| `worker.ts` | 878 | Poll loop, local queue, both service download paths, Jellyfin rename, subtitle invocation | `startWorker`, `cancelLocalDownload` | torbox, realdebrid, db, downloader, media-layout, electron |
| `media-layout.ts` | 235 | Release-name parser, TMDB resolve+memo, destination computation, path sanitizers | `parseReleaseName`, `tmdbResolve`, `computeDestination`, `safeSegment` | https |
| `metasearch.ts` | 323 | qBittorrent-plugin runner supervisor, batch + streaming | `MetaSearch`, `getMetaSearch`, `initMetaSearch`, `MetaResult`, `SearchProgress` | child_process, cf-fetcher |
| `cf-fetcher.ts` | 201 | Local HTTP bridge + hidden-window CF fetch | `startCFServer`, `getCFServerPort`, `stopCFServer` | electron BrowserWindow, http |
| `flaresolverr.ts` | 295 | Download/install/supervise FlareSolverr | `startFlareSolverr`, `stopFlareSolverr`, `restartFlareSolverr`, `getFlareSolverrStatus`, `getFlareSolverrUrl`, `isFlareSolverrReady` | child_process, https, http |
| `plugin-updater.ts` | 219 | Update qBittorrent `.py` engines from GitHub | `checkPluginsForUpdates`, `updatePlugins`, `PluginInfo`, `UpdateResult` | https, fs |
| `updater.ts` | 129 | `electron-updater` controller + circuit breaker | `initAutoUpdater`, `checkForUpdates`, `checkForUpdatesManual`, `downloadUpdate`, `installUpdate`, `dismissUpdate`, `isUpdateDownloaded` (unused) | `electron-updater`, db |
| `season-cache.ts` | 37 | In-memory per-(imdbId,season) resolved-episode cache | `getSeasonCache`, `saveSeasonCache` | — |

### 1.3 `electron/qbit-plugins/` — Python metasearch runtime

| File | Lines | Responsibility |
|---|---|---|
| `qbit-runner.py` | 297 | Loads every engine `.py`, runs 23 searches in a thread pool, normalizes/dedupes, prints JSON or JSON-lines in `--stream` mode. Exit codes: 0 ok, 1 usage, 2 load failure (unused), 3 no engines (`qbit-runner.py:11-16,195-297`). |
| `helpers.py` | 239 | qBittorrent `helpers` shim: `retrieve_url` tries cloudscraper → Electron bridge → FlareSolverr → requests (`helpers.py:187-234`); `download_file` returns the URL (debrid does the fetch). |
| `novaprinter.py` | 158 | qBittorrent result sink shim: per-thread accumulators (fixes cross-engine result theft), thread inheritance, `anySizeToBytes`, `SearchResults` dict. |
| `engines/*.py` | 23 files | Search plugins: `1337x, bitsearch, cinecalidad, elitetorrent, eztv, glotorrents, goggames, grantorrent, hacktorrent, limetorrents, mejortorrent, nyaasi, piratebay, pirateiro, sktorrent, snowfl, solidtorrents, torlock, torrentdownload, torrentdownloads, torrentproject, torrentscsv, wolfmax4k`. Summary only — each subclass of a qBittorrent engine template with `search()` + `url`. |
| `qbit-runner.exe` | 14.7 MB | PyInstaller build of `qbit-runner.py` (shipped in prod). |

### 1.4 `src/` — renderer modules

| Area | Files | Notes |
|---|---|---|
| Shell | `main.tsx`, `App.tsx`, `index.css`, `App.css` (empty), `index.html` | `App.tsx` is the single screen switcher (navbar string state, not a router). |
| Components (16) | `Sidebar`, `Header`, `MagnetBar`, `ServiceSelector`, `SearchTabsBar`, `SearchTab`, `DownloadCard`, `LogPanel`, `DiscoverView`, `DiscoverResults`, `DiscoverThumbnails`, `SeriesPicker`, `SeasonDownloadModal`, `SettingsModal`, `UpdateDialog`, `Toast` | Detailed props/states in `docs/ui-inventory.md`. |
| Stores | `store/searchTabs.ts`, `store/discoverTabs.ts` | Zustand; search tabs are per-query keys `"1"…"n"`; discover holds TMDB/season/episode/provider state. |
| Hooks | `hooks/useApi.ts`, `hooks/useDebridServices.ts` | `useApi` maps fake REST paths → `electronAPI` calls (§2.3). |
| i18n | `i18n/{en,es}.ts`, `i18n/index.tsx`, `i18n/LangToggle.tsx` | 127 leaf strings per locale, 10 namespaces, default Spanish (§ `docs/ui-inventory.md` i18n). |
| Types | `types/electron.ts` | Authoritative renderer-side contract for `electronAPI` (192 lines). |
| Tests | `__tests__/media-layout.test.ts` (12 cases), `__tests__/stores/searchTabs.test.ts`, `__tests__/stores/discoverTabs.test.ts`, `__tests__/setup.ts` | Only `media-layout` covers port-critical logic. |

### 1.5 Root config

| File | Role / constants |
|---|---|
| `package.json` | name `tordownloader-pro`, version `1.0.18`, main `dist-electron/main.js`; deps include `better-sqlite3 ^12.11.1`, `electron-updater ^6.8.9`, Radix UI, Zustand, GSAP, Tailwind 3.4, Electron 42.5 (`package.json:1-60`) |
| `electron-builder.yml` | NSIS x64, `appId com.tordownloader.pro`, GitHub publish `latinokodi/tordownloader-pro`, `asar: true`, extraResources for the four exes (`electron-builder.yml:1-47`) |
| `vite.config.ts` | Vite 6 + vite-plugin-electron; stubs `node:sqlite`; externals `better-sqlite3` (`vite.config.ts:7-48`) |
| `tailwind.config.js`, `src/index.css` | design tokens (see `docs/ui-inventory.md`) |
| `index.html` | Google Fonts Inter + JetBrains Mono; **no CSP meta tag** (`index.html:1-13`) |
| `INAPP-UPDATE.md`, `README.md` | design/behaviour prose; both are secondary evidence and flagged where they disagree with code (§9) |

### 1.6 Excluded / summarized

- `electron/flaresolverr/**` — a vendored 250 MB+ Windows FlareSolverr distribution including Chromium 142 (`chrome.dll` 277 MB) and Python 3.11 runtime. Only its *role* matters for parity; contents were not analyzed.
- `electron/qbit-plugins/engines/*.py` — summarized by name per instruction; `qbit-runner.py`/`helpers.py`/`novaprinter.py` (the protocol) were read in full.
- `dist/`, `dist-electron/`, `release/`, `node_modules/`, `build/`, `.hermes/`, `.qwen/`, `torbox-downloader.db` (0 bytes) — build outputs/artifacts.

---

## 2. IPC surface

All channels are registered in `electron/main.ts` (47 `ipcMain.handle` calls, verified by grep) and wrapped in `electron/preload.ts` (58 exported functions: 47 invoke wrappers + 11 event subscriptions; several event wrappers share one main-process event family). `contextBridge.exposeInMainWorld` is the only renderer↔main path (`electron/preload.ts:121`).

### 2.1 Invoke channels (renderer → main)

Shapes are as observed in the handler bodies; "raw" means the response object of the Python/HTTP client is returned untouched (no envelope normalization).

| # | Channel | Preload API | Arguments | Success shape | Failure shape | Portability |
|---|---|---|---|---|---|---|
| 1 | `get-settings` | `getSettings` | — | `Settings` row incl. all tokens/secrets | throws | direct |
| 2 | `set-settings` | `setSettings` | `Partial<Settings>` (any object!) | `{success:true}` | throws | direct |
| 3 | `search-metasearch` | `searchMetaSearch(query, tabId?)` | `query: string`, `tabId: string\|null` | `{success:true, data: MetaResult[]}` (after stream ends; interim results via `search-progress`) | `{success:false, error}` | redesign (streaming) |
| 4 | `get-downloads` | `getDownloads` | — | `Download[]` (`created_at DESC`) | throws | direct |
| 5 | `get-season-cache` | `getSeasonCache` | `imdbId, season` | `{found:boolean, episodes: Record<string, unknown[]>}` | never fails | direct (in-memory) |
| 6 | `save-season-cache` | `saveSeasonCache` | `imdbId, season, episodes` | `{success:true}` | never fails | direct |
| 7 | `get-downloaded-episodes` | `getDownloadedEpisodes` | `title: string, season: number` | `{episodes:number[], queued:number[]}` | never fails (best-effort scan) | redesign (SAF scan) |
| 8 | `add-magnet` | `addMagnet` | `magnet, service='torbox', type='', season=null, episode=null` | raw TorBox `{success,data,...}` / RD `{success,data}` | `{success:false, error}` | direct |
| 9 | `add-torrent-url` | `addTorrentUrl` | `url, service, type, season, episode` | raw service response | `{success:false, error}` | direct |
| 10 | `torbox-has-hash` | `torboxHasHash` | `hash` | `{has:boolean, error?}` | `{has:false, error}` | direct |
| 11 | `control-torrent` | `controlTorrent` | `torrentId, operation` | raw TorBox response; op `delete` also deletes the DB row | `{success:false, error}` | direct |
| 12 | `cancel-download` | `cancelDownload` | `torrentId` | `{success:true}` — DB row deleted **before** cloud delete | always success | direct |
| 13 | `clear-completed` | `clearCompleted` | — | `{success:true}` | never fails | direct |
| 14 | `auth-start` | `authStart` | — | TorBox `/user/auth/device/start` JSON (`code`, `device_code`, `friendly_verification_url`, …) | `{success:false, error}` | direct |
| 15 | `auth-poll` | `authPoll` | `deviceCode` | `{success, data}`; on success persists `torbox_token` | raw error object | direct |
| 16 | `torbox-user-info` | `getUserInfo` | — | TorBox `/user/me` raw | `{success:false, error}` | direct |
| 17 | `rd-auth-start` | `rdAuthStart` | — | `{success:true, data:{device_code,user_code,verification_url,…}}` | `{success:false, error}` | direct |
| 18 | `rd-auth-poll` | `rdAuthPoll` | `deviceCode` | `{success:true, data:{access_token}}` and persists 4 RD secrets | `{success:false, pending:true}` (not authorized yet) / `{success:false,error}` | direct |
| 19 | `rd-user-info` | `rdUserInfo` | — | `{success, data}` RD `/user` | `{success:false, error: 'No token'}` | direct |
| 20 | `rd-traffic` | `rdTraffic` | — | `{success, data}` RD `/traffic` | `{success:false, error}` | direct |
| 21 | `rd-select-files` | `rdSelectFiles` | `torrentId` | `{success, data}` RD select result; sets cloud status `downloading` | `{success:false, error}` | direct |
| 22 | `test-metasearch` | `testMetaSearch` | (ignored `{url,api_key}`) | `{success:true, detail:'MetaSearch ready — N results…'}` | `{success:false, error, detail}` | redesign |
| 23 | `select-folder` | `selectFolder` | — | `string \| null` (native directory dialog) | — | **Electron-specific → ACTION_OPEN_DOCUMENT_TREE** |
| 24 | `get-logs` | `getLogs` | — | copy of in-memory `LogEntry[]` (≤500) | never fails | direct |
| 25 | `get-app-version` | `getVersion` | — | `app.getVersion()` string | — | replace (BuildConfig) |
| 26 | `open-folder` | `openFolder` | `folderPath: string` | `{success:boolean, error:string\|null}` from `shell.openPath` | non-empty error string | **Electron-specific → ACTION_VIEW / SAF** |
| 27 | `check-plugins` | `checkPlugins` | — | `{success:true, data: PluginInfo[]}` | `{success:false, error}` | drop/redesign |
| 28 | `update-plugins` | `updatePlugins` | — | `{success:true, data:{plugins,updated,errors}}` | `{success:false, error}` | drop |
| 29 | `tmdb-lists` | `tmdbLists` | — | `{success:true, data:{movies:{…},tv:{…}}}` | `{success:false, error}` | direct (reimplement HTTP) |
| 30 | `tmdb-detail` | `tmdbDetail` | `tmdbId, mediaType` | `{success:true, data: detail}` | `{success:false, error}` | direct |
| 31 | `tmdb-season` | `tmdbSeason` | `tmdbId, seasonNumber` | `{success:true, data:{season_number, episodes[]}}` | `{success:false, error}` | direct |
| 32 | `tmdb-search` | `tmdbSearch` | `query` | `{success:true, data: item[]}` | `{success:false, error}` | direct |
| 33 | `tmdb-validate` | `tmdbValidate` | `apiKey` | `{success:true}` and persists key | `{success:false, error}` | direct |
| 34 | `latino-search` | `latinoSearch` | `imdbId, mediaType, season?, episode?` | **`undefined`** (Promise<void>); results only via `latino-search-progress` events | swallows spawn errors | redesign (Flow) |
| 35 | `latino-search-batch` | `latinoSearchBatch` | same | `{success:true, data: DiscoverResult[]}` | `{success:false, error, data:[]}` | direct |
| 36 | `search-batch` | `searchBatch` | `query` | `{success:true, data: MetaResult[]}` | `{success:false, error, data:[]}` | direct |
| 37 | `comet-manifest` | `cometManifest` | — | `{success:true, data: manifest}` | `{success:false, error}` | direct |
| 38 | `comet-streams` | `cometStreams` | `type, imdbId, season?, episode?` | `{success:true, data: streams[]}` | `{success:false, error, data:[]}` | direct |
| 39 | `catalog-manifest` | `catalogManifest` | — | `{success:true, data: catalogs[]}` (Comet returns `[]`) | `{success:false, error}` | drop (Comet has no catalogs) |
| 40 | `catalog-items` | `catalogItems` | `type, id` | `{success:true, data: metas}` | `{success:false, error}` | drop |
| 41 | `catalog-meta` | `catalogMeta` | `type, imdbId` | `{success:true, data: meta}` | `{success:false, error}` | keep only if a meta addon is kept |
| 42 | `check-for-updates` | `checkForUpdates` | — | `{success:true}`; result arrives on update events | — | **Electron-specific → drop / Play** |
| 43 | `download-update` | `downloadUpdate` | — | `{success:true}` | — | drop |
| 44 | `install-update` | `installUpdate` | — | `{success:true}` (calls `quitAndInstall`) | — | drop |
| 45 | `dismiss-update` | `dismissUpdate` | — | `{success:true}` (writes `last_update_prompt`) | — | drop |
| 46 | `flaresolverr-restart` | `flaresolverrRestart` | — | `{success:boolean}` | — | **not portable** (§7) |
| 47 | `flaresolverr-status` | `flaresolverrStatus` | — | `{status:'ready'\|'starting'\|'failed'\|'off'}` | — | not portable |

Notes:
- Handlers 1, 2, 4 and 24 are synchronous (no `await`) and will block the main process while SQLite runs.
- Error convention is inconsistent: some handlers return `{success:false,error}` themselves, others let the raw client envelope (`{success:false,...}`) through, and 3/34/47 can resolve `undefined`. The renderer must treat any non-`{success:true}` as failure.
- `comet-manifest`/`comet-streams` were added in the uncommitted working-tree change (§0.3) and are now exposed in `preload.ts:89-92`.
- `latino-search` (34) is the only channel whose return value carries no data; `DiscoverView` relies entirely on `onLatinoSearchProgress` (`src/components/DiscoverView.tsx:100-114`).

### 2.2 Main → renderer events

| Event | Payload | Producer | Consumer |
|---|---|---|---|
| `downloads-updated` | — | worker + add/cancel/clear handlers (`electron/worker.ts:160-162` and 15 call sites) | `App.tsx` refresh on every worker tick |
| `app-log` | `{ts:'HH:MM:SS', text, level:'info'\|'warn'\|'error'}` | global `console.*` interception (`electron/main.ts:54-60`) | `LogPanel.tsx:29-35` |
| `search-progress` | `{tabId, type:'engine_start'\|'engine_results'\|'done', engine?, results?, total?}` | `search-metasearch` handler (`electron/main.ts:168-183`) | `searchTabs` store |
| `search-error` | `{tabId, error}` | same handler | `searchTabs` store |
| `latino-search-progress` | raw JSON line from `latino-providers.py` (`provider_start`/`provider_results`/`done`) | `latino-search` handler (`electron/main.ts:998-1013`) | `DiscoverView.tsx:100-114` |
| `flaresolverr-ready` | — | startup + manual restart | `Sidebar` status |
| `update-available` | `version: string` | `electron/updater.ts:26-32` | `UpdateDialog` |
| `update-not-available` | — | `electron/updater.ts:34-38` | Settings button |
| `update-download-progress` | `percent: number` | `electron/updater.ts:40-44` | `UpdateDialog` |
| `update-downloaded` | — | `electron/updater.ts:46-50` | `UpdateDialog` |
| `update-error` | `message: string` | `electron/updater.ts:52-60` | toast |

### 2.3 Renderer abstraction: fake REST paths → IPC

`src/hooks/useApi.ts` (52 lines) maps string paths to `electronAPI` calls. This mapping is the de-facto renderer data layer; a Compose port needs equivalent repository methods, not HTTP.

| `api(path, method, body)` | Actual IPC |
|---|---|
| `GET /settings` | `getSettings()` |
| `POST /settings` | `setSettings(body)` |
| `GET /downloads` | `getDownloads()` |
| `POST /downloads/add` | `addMagnet(body.magnet, body.service, body.type, body.season, body.episode)` |
| `POST /downloads/add-torrent-url` | `addTorrentUrl(body.url, …)` |
| `POST /downloads/clear-completed` | `clearCompleted()` |
| `DELETE /downloads/:id` | `controlTorrent(id, 'delete')` (note: the id passed by `App.tsx` is `torbox_id`) |
| `POST /downloads/cancel/:id` | `cancelDownload(id)` |
| `POST /search` | `searchMetaSearch(body.query, body.tabId ?? null)` |
| `GET /select-folder` | `selectFolder()` → `{path}` |
| `POST /auth/start` | `authStart()` |
| `GET /auth/user` | `getUserInfo()` |
| `POST /auth/poll/:deviceCode` | `authPoll(deviceCode)` |
| `POST /settings/test-metasearch` | `testMetaSearch()` |
| `GET /plugins/check` | `checkPlugins()` |
| `GET /plugins/update` | `updatePlugins()` |
| `POST /rd/auth/start` | `rdAuthStart()` |
| `POST /rd/auth/poll/:deviceCode` | `rdAuthPoll(deviceCode)` |
| `GET /rd/user` | `rdUserInfo()` |
| `GET /rd/traffic` | `rdTraffic()` |
| `POST /rd/select-files` | `rdSelectFiles(body.torrentId)` |

Anything unmatched returns `{success:true}` and does nothing (`useApi.ts:54`) — a silent no-op that hides typos.

---

## 3. Data model

### 3.1 Database

| Property | Value |
|---|---|
| Engine | `better-sqlite3` ^12.11.1, synchronous API (`electron/db.ts:1`, `package.json:24`) |
| File | `<userData>/tordownloader.db` (`electron/db.ts:5`) |
| `userData` | forced to `%APPDATA%\tordownloader-pro` (`electron/main.ts:24-25`); `app.setName('tordownloader-pro')` alone was insufficient on Electron 42 (comment `electron/main.ts:17-23`) |
| Journal | `PRAGMA journal_mode = WAL` (`electron/db.ts:10`) |
| Tables | `settings`, `downloads`; no `seasons` table, no `logs` table, no migration version table |
| Indexes | only the implicit UNIQUE index on `downloads.torbox_id` (`electron/db.ts:30`); **no explicit `CREATE INDEX`** |
| Init failure handling | one retry after 2 s if `initDB` throws, e.g. second instance holding the file (`electron/main.ts:108-117`) |

### 3.2 `settings` — exact DDL (`electron/db.ts:13-26`)

```sql
CREATE TABLE IF NOT EXISTS settings (
  id INTEGER PRIMARY KEY,
  torbox_token VARCHAR DEFAULT '',
  realdebrid_token VARCHAR DEFAULT '',
  realdebrid_refresh_token VARCHAR DEFAULT '',
  realdebrid_client_id VARCHAR DEFAULT '',
  realdebrid_client_secret VARCHAR DEFAULT '',
  destination_folder VARCHAR DEFAULT '',
  movies_folder VARCHAR DEFAULT '',
  series_folder VARCHAR DEFAULT '',
  auto_remove_completed BOOLEAN DEFAULT 0,
  tmdb_api_key VARCHAR DEFAULT '',
  last_update_prompt VARCHAR DEFAULT ''
);
```

Default row: `INSERT INTO settings (id) VALUES (1)` only when the table is empty (`electron/db.ts:68-71`). `getSettings()` always reads `WHERE id = 1` (`electron/db.ts:110-111`).

### 3.3 `downloads` — exact DDL (`electron/db.ts:28-44`)

```sql
CREATE TABLE IF NOT EXISTS downloads (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  torbox_id VARCHAR UNIQUE,
  name VARCHAR DEFAULT 'Pending...',
  status VARCHAR DEFAULT 'downloading',
  progress INTEGER DEFAULT 0,
  local_status VARCHAR DEFAULT 'pending',
  local_progress INTEGER DEFAULT 0,
  local_speed INTEGER DEFAULT 0,
  local_eta VARCHAR DEFAULT '',
  local_path VARCHAR,
  service VARCHAR DEFAULT 'torbox',
  type VARCHAR DEFAULT '',
  season INTEGER,
  episode INTEGER,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

### 3.4 Migrations (`electron/db.ts:47-65`)

Applied at every startup in this order, each wrapped in `try { } catch { /* Column already exists */ }`:

1. `downloads.local_eta VARCHAR DEFAULT ''`
2. `downloads.service VARCHAR DEFAULT 'torbox'`
3. `settings.realdebrid_token`
4. `settings.realdebrid_refresh_token`
5. `settings.realdebrid_client_id`
6. `settings.realdebrid_client_secret`
7. `settings.tmdb_api_key`
8. `settings.last_update_prompt`
9. `settings.movies_folder`
10. `settings.series_folder`
11. `downloads.type`
12. `downloads.season`
13. `downloads.episode`

There is no schema version, no rollback, no data migration (all `ADD COLUMN` with defaults); a rename or type change is not possible with this mechanism. Android/Room needs a real migration plan starting at the current shape; the sequence above is the historical truth.

### 3.5 TypeScript contracts

`Settings` (`electron/db.ts:76-89`): `id, torbox_token, realdebrid_token, realdebrid_refresh_token, realdebrid_client_id, realdebrid_client_secret, destination_folder, movies_folder, series_folder, auto_remove_completed (boolean), tmdb_api_key, last_update_prompt`.

`Download` (`electron/db.ts:91-107`): `id:number, torbox_id:string, name:string, status:string, progress:number, local_status:string, local_progress:number, local_speed:number, local_eta:string, local_path:string|null, service:'torbox'|'realdebrid', type:'movie'|'series'|'', season:number|null, episode:number|null, created_at:string`.

`getSettings()` converts the SQLite integer to boolean: `auto_remove_completed: Boolean(row.auto_remove_completed)` (`electron/db.ts:112-115`). Writes convert back: `typeof value === 'boolean' ? (value ? 1 : 0) : value` (`electron/db.ts:125`).

### 3.6 Settings fields — default, sensitivity, consumer

| Field | Schema default | Sensitive | Read by (evidence) |
|---|---|---|---|
| `id` | 1 (single row) | no | all queries |
| `torbox_token` | `''` | **yes (bearer)** | `TorboxAPI` construction (`electron/main.ts:332,470,490,521,560,590`; `worker.ts:44,172`) |
| `realdebrid_token` | `''` | **yes (bearer)** | `RealDebridAPI` construction (`electron/main.ts:286,380,518,543,651,662,673`; `worker.ts:44,217`) |
| `realdebrid_refresh_token` | `''` | **yes** | written at link time (`electron/main.ts:636`); **no consumer anywhere** (§6.3, §9) |
| `realdebrid_client_id` | `''` | semi | written at link time (`electron/main.ts:637`); no consumer after that |
| `realdebrid_client_secret` | `''` | **yes** | written at link time (`electron/main.ts:638`); no consumer after that |
| `destination_folder` | `''` | no | flat-download root; fallback root for movies/series (`electron/worker.ts:305-315`; `electron/media-layout.ts:203-210`); "Open Dest" button (`src/App.tsx:170-176`) |
| `movies_folder` | `''` | no | movie layout root (`electron/media-layout.ts:203-206`) |
| `series_folder` | `''` | no | series layout root + episode-scan fallback (`electron/media-layout.ts:203-206`; `electron/main.ts:259`) |
| `auto_remove_completed` | `0` (false) | no | worker deletes cloud transfer + DB row after local completion (`electron/worker.ts:206-209,292-295`) |
| `tmdb_api_key` | `''` | **yes (API key)** | passed as env var to `tmdb-provider.py` (`electron/main.ts:785`) and in TMDB URLs (`electron/media-layout.ts:154`) |
| `last_update_prompt` | `''` | no | 24 h dismiss cooldown (`electron/updater.ts:74-80,121-125`) |

### 3.7 Download record lifecycle

#### Cloud status (`status`)

Written by the worker from the debrid list each poll (`electron/worker.ts:188-192,233-237`), and at add time:

| Producer | Value | Evidence |
|---|---|---|
| add (TorBox) | `'pending'` | `electron/main.ts:354` |
| add (RD) | `'waiting_files_selection'` | `electron/main.ts:308,402` |
| RD after auto select | `'downloading'` | `electron/main.ts:689`; `worker.ts:253` writes the real status |
| TorBox normalizer | `download_state` → else `download_status` → else `'completed'` if `download_finished`/`download_present` → else `'unknown'` | `electron/torbox.ts:38-43` |
| RD normalizer | raw `status` untouched | `electron/realdebrid.ts:132-136` |

Completion sets recognized by the worker: TorBox `['completed','cached','finished']` (`electron/worker.ts:196`), RD `['downloaded','finished']` (`electron/worker.ts:282`). RD `magnet_conversion` is re-queried via `/torrents/info/{id}` because the list endpoint reports it stale (`electron/worker.ts:241-263`).

The UI also declares these cloud phase keys (used only for label lookup): `magnet_conversion, waiting_files_selection, queued, downloading, downloaded, finished, completed, cached, error, virus, dead, compressing, uploading, magnet_error` (`src/i18n/en.ts:53-71`).

#### Local status (`local_status`) — exact string formats

| Value / format | Set at | Notes |
|---|---|---|
| `pending` | DB default (`electron/db.ts:34`); re-armed on re-add of failed transfer (`electron/main.ts:298,344,392,438`) and on startup resume (`electron/worker.ts:138`) | queueable |
| `queued` | `electron/worker.ts:201,287` | waiting for a download slot |
| `Downloading {i}/{n}...` | `electron/worker.ts:404,596` | `i = idx+1`, `n = file/link count` |
| `Downloading {i}/{n} (intento {attempt})...` | same lines, when `attempt > 1` | Spanish literal "intento" is part of the persisted string |
| `Downloading {i}/{n} ({pct}%)` | `electron/worker.ts:426,618` | `pct = Math.floor(progress_percent)` of the current file |
| `completed` | `electron/worker.ts:489,683` | terminal success |
| `failed: {err.message}` | `electron/worker.ts:49,506,699` | terminal failure; message is free text and may embed a URL (§6.4) |

UI translation of these strings is regex/prefix-based (`src/components/DownloadCard.tsx:34-62`): `/^downloading (\d+)\/(\d+)/` → `Downloading i/n`; prefix `failed` → `Failed: <msg.slice(6)>`; exact `queued`; then dictionary lookup in `t.downloadCard.phases`; otherwise capitalize.

Display precedence (`src/components/DownloadCard.tsx:64-76`): local `completed` → "Complete 100%"; local `failed` → "Local failed"; cloud done → shows the *local* status (or Pending) with `local_progress`; otherwise cloud status with `progress`. A card is "active" unless (cloud done && local completed) or local failed (`DownloadCard.tsx:91-98`).

#### State machine (single transfer)

```
add-magnet/add-torrent-url
   └─ row inserted (local_status='pending', cloud status 'pending'|'waiting_files_selection')
worker poll (10 s)
   ├─ cloud done && local in {pending,queued} && not already queued
   │     → local_status='queued' → FIFO queue (max 3 active)
   ├─ queue slot free → runTorboxDownload / runRealdebridDownload
   │     ├─ resolveDestination()  (Jellyfin layout or flat folder)
   │     ├─ per file/link: local_status='Downloading i/n...' → '(pct%)'
   │     ├─ success → local_status='completed', local_progress=100, local_speed=0
   │     ├─ subtitles (media only) → rename (series only)
   │     └─ throw → local_status='failed: <msg>'
   └─ auto_remove_completed && cloud done && local_status='completed'
         → delete cloud torrent + delete DB row
cancel-download → abort Downloader, delete DB row immediately, cloud delete fire-and-forget
clear-completed → for rows (cloud ∈ {completed,downloaded,cached,finished}) AND local='completed'
                  → delete cloud torrent + delete DB row
startup → cloud done && local in {downloading*, pending} → local_status='pending' (re-queue)
```

#### Startup resume (`electron/worker.ts:128-143`)

For every row: if cloud status ∈ `['completed','cached','finished']` and local status starts with `downloading` **or** equals `pending`, log "Resuming interrupted download" and reset `local_status='pending'`. Note: RD's `'downloaded'` is **not** in this list, so an interrupted RD transfer whose cloud state is exactly `downloaded` is not auto-rearmed by name — it is nevertheless picked up by the poll (`worker.ts:284`) because it is `pending`; this asymmetry is real but harmless.

### 3.8 Non-persisted state

- **Season cache**: in-memory `Map<string,{ts,episodes}>`, key `${imdbId}:${season}`, max **100** entries, evicts oldest `ts` on overflow (`electron/season-cache.ts:12-37`). Lost on restart; explicitly described as session-only (`season-cache.ts:1-5`).
- **Logs**: in-memory ring buffer of **500** entries, `{ts:'HH:MM:SS', text, level}`; global `console.*` interception (`electron/main.ts:41-60`). Not persisted. The renderer keeps its own 500-entry copy (`src/components/LogPanel.tsx:30-34`).
- **Active downloads**: `Map<torboxId,true>` + `Map<torboxId,Downloader>` in the worker (`electron/worker.ts:13-14`).

---

## 4. Service APIs

All HTTP is `axios` in the main process except the Python sidecars (stdlib `urllib`) and plugin fetching (`https.get`). There is no proxy configuration anywhere in the source — Tor routing on Android must be a deliberate, documented egress change (§7.1).

### 4.1 TorBox (`electron/torbox.ts`)

| Property | Value |
|---|---|
| Base URL | `https://api.torbox.app/v1/api` (`torbox.ts:4`) |
| Headers | `User-Agent: TorboxDownloader/2.0`, `Accept: application/json`, `Authorization: Bearer <token>` when token present (`torbox.ts:10-14`) |
| Default timeout | 30 000 ms (`torbox.ts:15`); 60 000 ms for create/upload (`torbox.ts:100,124`) |
| Response style | raw JSON returned; TorBox envelope uses a `success` field |

| Method | Path | Params / body | Function | Evidence |
|---|---|---|---|---|
| GET | `/user/auth/device/start` | `?app=TorboxDownloader` | device code (`code`, `device_code`, `friendly_verification_url`) | `torbox.ts:67-73` |
| POST | `/user/auth/device/token` | `{device_code}` | exchange for token | `torbox.ts:75-81` |
| GET | `/user/me` | — | user info | `torbox.ts:83-88` |
| POST | `/torrents/createtorrent` | form: `magnet`, `seed=0`, `allow_zip=false` | add magnet | `torbox.ts:90-105` |
| POST | `/torrents/createtorrent` | multipart: file (`application/x-bittorrent`), `seed=0`, `allow_zip=false` | add .torrent | `torbox.ts:107-129` |
| GET | `/torrents/mylist` | `?bypass_cache=true` | list + normalize | `torbox.ts:164-174` |
| GET | `/torrents/mylist` | `?id=<id>` | info + normalize (array or object) | `torbox.ts:176-188` |
| GET | `/torrents/requestdl` | `?torrent_id&file_id&token=<token>` | signed download URL (**token travels in the query string**) | `torbox.ts:190-196` |
| POST | `/torrents/controltorrent` | form: `torrent_id`, `operation` (`Delete`, …) | pause/delete | `torbox.ts:198-211` |

`addTorrentFromUrl`: GETs the external `.torrent` (30 s, UA) and derives the filename from `Content-Disposition` with regex `filename[^;=\n]*=((['"]).*?\2|[^;\n]*)`, else from a `.torrent` path segment (`torbox.ts:132-162`).

Normalization helpers (must be ported 1:1):

- `torrentIdentity(data)` → `{ id: String(data.id || data.torrent_id || '').trim(), hash: String(data.hash || '').trim().toLowerCase() }` (`torbox.ts:19-23`).
- `normalizeProgress(value, state)` → `100` if state ∈ `completed|cached|finished`; else `Number(value||0)`, `NaN→0`, `0<raw<=1 → raw*100`, then `clamp(0,100, floor)` (`torbox.ts:25-34`).
- `normalizeTorrent(data)` → shallow copy; `download_state = download_state || download_status || (download_finished||download_present ? 'completed' : 'unknown')`; `progress = normalizeProgress(...)` (`torbox.ts:36-46`).

Error mapping (`torbox.ts:48-65`): `ECONNABORTED` → `{success:false,error:'Timeout',detail:'The request to TorBox timed out.'}`; HTTP error with body → body returned verbatim (TorBox JSON errors); else `{success:false,error:'RequestError',detail:e.message}`; non-Axios → `{success:false,error:'UnknownError',detail:String(error)}`.

Device-code flow as observed end-to-end: UI `Link Torbox via OAuth` → `auth-start` → shows `authData.code` and link `authData.friendly_verification_url || "https://tor.box/link"` → polls `auth-poll` every **5 000 ms** (`src/components/SettingsModal.tsx:238-260,322-333`); `auth-poll` stores the token if `data` is a string or `data.access_token || data.token` (`electron/main.ts:567-584`); UI reloads settings and fetches `/user/me` (`SettingsModal.tsx:48-57,249-253`).

### 4.2 Real-Debrid (`electron/realdebrid.ts`)

| Property | Value |
|---|---|
| OAuth base | `https://api.real-debrid.com/oauth/v2` (`realdebrid.ts:8`) |
| REST base | `https://api.real-debrid.com/rest/1.0` (`realdebrid.ts:9`) |
| Open-source client id | `X245A4XAIBGVM` (`realdebrid.ts:6`) |
| Headers | `User-Agent: TorDownloader/2.0`, `Accept: application/json`, `Authorization: Bearer <token>` when present (`realdebrid.ts:17-21`) |
| Default timeout | 30 000 ms (`realdebrid.ts:22`); OAuth 15 000 ms (`realdebrid.ts:35,50,81,107`); add 60 000 ms (`realdebrid.ts:182,199`) |
| Response style | **envelope** `{success:true,data}` / `{success:false,error,detail}` — different from TorBox |

OAuth device flow (4 steps):

1. `GET /oauth/v2/device/code?client_id=<id>&new_credentials=yes` → `{success:true,data:{device_code,user_code,verification_url,expires_in,interval}}` (`realdebrid.ts:29-41`). UI shows `user_code` and `verification_url || "https://real-debrid.com/device"` (`SettingsModal.tsx:355-364`).
2. `GET /oauth/v2/device/credentials?client_id=<id>&code=<device_code>` → per-user `client_id`/`client_secret`; HTTP **400/403 is treated as "pending", not an error** (`realdebrid.ts:44-61`).
3. `POST /oauth/v2/token` form: `client_id`, `client_secret`, `code=<device_code>`, `grant_type=http://oauth.net/grant_type/device/1.0` → `access_token` + `refresh_token` (`realdebrid.ts:64-87`).
4. Persist: `realdebrid_token`, `realdebrid_refresh_token`, `realdebrid_client_id`, `realdebrid_client_secret` (`electron/main.ts:634-639`); returns only `{success:true,data:{access_token}}`.

Poll cadence: **5 000 ms** (`SettingsModal.tsx:76-88`). `refreshAccessToken(clientId, clientSecret, refreshToken)` exists (`realdebrid.ts:90-113`) but **is never called** (grep: only definition + column writes). Consequence: when the RD access token expires the app surfaces `AuthError` from the next REST call and the user must re-link; user-visible status refresh stops.

REST endpoints:

| Method | Path | Body | Function | Evidence |
|---|---|---|---|---|
| GET | `/user` | — | account info | `realdebrid.ts:166-168` |
| GET | `/traffic` | — | traffic quota | `realdebrid.ts:170-172` |
| POST | `/torrents/addMagnet` | form `magnet` | add magnet | `realdebrid.ts:174-185` |
| PUT | `/torrents/addTorrent` | multipart file | add .torrent | `realdebrid.ts:187-202` |
| GET | `/torrents` | — | list (normalized) | `realdebrid.ts:232-238` |
| GET | `/torrents/info/{id}` | — | detail (normalized) | `realdebrid.ts:240-246` |
| POST | `/unrestrict/link` | form `link` | direct URL + filename + filesize | `realdebrid.ts:248-258` |
| DELETE | `/torrents/delete/{id}` | — | delete | `realdebrid.ts:260-262` |
| POST | `/torrents/selectFiles/{id}` | form `files=id,id,…` | select files | `realdebrid.ts:264-274` |

Notes: `torrentIdentity` = `{id: String(data.id||'').trim(), hash: String(data.hash||'').trim().toLowerCase()}` (`realdebrid.ts:117-121`); `normalizeProgress` returns 100 for `downloaded|finished`, same fraction/clamp logic as TorBox (`realdebrid.ts:123-130`); `normalizeTorrent` only rewrites `progress` (`realdebrid.ts:132-136`). Error mapping adds `401/403 → {success:false,error:'AuthError',detail:'Real-Debrid token is invalid or expired.'}` and prefers `data.error || data.error_details` for other 4xx (`realdebrid.ts:140-162`).

### 4.3 Meta-search runner (`electron/metasearch.ts` + `qbit-plugins/`)

Invocation:

- Dev: `python <repo>/electron/qbit-plugins/qbit-runner.py …` (`metasearch.ts:31-38,40-46`).
- Prod: `<resources>/qbit-plugins/qbit-runner.exe …` (same lines).
- Environment per spawn: `CF_COOKIE_FILE=<userData>/cf_cookies.json`, `ELECTRON_CF_PORT=<cf-fetcher port>`, `USER_PLUGINS_DIR=<userData>/plugins/engines` (`metasearch.ts:48-54,148-157,241-246`).
- Dependency bootstrap only in dev: `python -c "import cloudscraper"`, else `pip install --quiet cloudscraper` (`metasearch.ts:60-100`). Prod assumes the exe bundles it.

Protocol (streaming mode, used by the UI):

| Line | Shape | Producer |
|---|---|---|
| engine_start | `{"type":"engine_start","engine":"1337x"}` — one per loaded engine before searches start | `qbit-runner.py:250-255` |
| engine_results | `{"type":"engine_results","engine":…,"results":[…]}` — one per engine as it finishes | `qbit-runner.py:264-279` |
| done | `{"type":"done","total":N,"results":[…]}` — authoritative deduplicated set | `qbit-runner.py:287-291` |

Parent behavior: spawn `runner --stream "<query>"`, split stdout on `\n`, parse each non-empty line, forward `engine_start`/`engine_results` to the renderer via `search-progress`, keep only `done.results` as the final array (`metasearch.ts:209-304`). Batch mode spawns `runner "<query>"` and parses the whole stdout as one JSON array (`metasearch.ts:135-207`). Timeout **90 000 ms**, then `SIGTERM` and `resolve([])` (batch) / `onError('Search timed out')` (stream) (`metasearch.ts:104,159-166,249-257`). Non-zero exit stderr is logged (first 500 chars).

Result normalization (`qbit-runner.py:54-104`): `{title, size, seeders, peers, link, indexer, info_hash}`; `size` is converted from raw bytes to `"1.5 GB"`/`"700.0 MB"`/`"… KB"`/`"N B"` when the plugin reported a byte count; `seeders = max(0,int(seeds))`, `peers = max(0,int(leech))`; `indexer = raw.engine_url`; `info_hash` extracted from `btih:<40 hex>` or base32 `btih:<32>` (`qbit-runner.py:41-51`).

Dedupe/sort (`qbit-runner.py:183-192`): key = `info_hash || link`, keep the entry with the highest seeders, output sorted by seeders descending.

Engine execution: `ThreadPoolExecutor(max_workers=min(len(plugins),8))`, `as_completed(timeout=45)`, per-future `result(timeout=30)` (`qbit-runner.py:244-282`). Plugin precedence: user plugins in `USER_PLUGINS_DIR` override bundled engines of the same filename (`qbit-runner.py:212-217`). Load failure is printed to stderr; if **no** engine loads it prints `[]` and exits **3** (`qbit-runner.py:230-240`).

Engine list (23 files, `electron/qbit-plugins/engines/`): `1337x, bitsearch, cinecalidad, elitetorrent, eztv, glotorrents, goggames, grantorrent, hacktorrent, limetorrents, mejortorrent, nyaasi, piratebay, pirateiro, sktorrent, snowfl, solidtorrents, torlock, torrentdownload, torrentdownloads, torrentproject, torrentscsv, wolfmax4k`. The UI claims "17 engines bundled" (`src/i18n/en.ts:84`) — mismatch flagged in §9.

Fetch strategy inside plugins (`helpers.py:187-234`): cloudscraper (Chrome 125 UA, `delay=0.5`) → if CF challenge, Electron bridge (`POST http://127.0.0.1:<port>/fetch`) → FlareSolverr (`POST /v1 {"cmd":"request.get","url":…,"maxTimeout":60000}`) → plain requests → raise with a diagnostic listing which backends were available. CF challenge detection list: `just a moment`, `checking your browser`, `cf-browser-verification`, `cf-challenge-running`, `#challenge-running` (`helpers.py:109-120`).

### 4.4 Cloudflare Chromium bridge (`electron/cf-fetcher.ts`)

- Local HTTP server bound to `127.0.0.1`, **random port** (`listen(0)`), CORS `Access-Control-Allow-Origin: *` (`cf-fetcher.ts:134-149,181-189`).
- Only route: `POST /fetch`; body `{"url":"…"}`; response `200 {"html":"…"}` or `400/502/500 {"error":"…"}` (`cf-fetcher.ts:146-177`).
- Fetch: hidden 1024×768 `BrowserWindow` per request with `nodeIntegration:false, contextIsolation:true` and a unique partition `cf_fetcher_<Date.now()>`; loads the URL, then polls the DOM every **2 000 ms**, max **20** polls (≈40 s) after load, overall `fetchViaBrowser` timeout **60 000 ms** (`cf-fetcher.ts:34-127,164`). Challenge detection uses the first 500 chars against 5 indicators (`cf-fetcher.ts:18-30`). On timeout it still returns whatever outerHTML exists (`cf-fetcher.ts:96-107`).
- No URL allowlist and no authentication on the bridge — any local process or any web page (CORS `*`) can make the app's Chromium fetch arbitrary URLs (§6.5).

### 4.5 FlareSolverr manager (`electron/flaresolverr.ts`)

Constants: port **8191**, health interval **30 000 ms**, startup timeout **60 000 ms**, max restarts **3** (`flaresolverr.ts:18-21`); release API `https://api.github.com/repos/FlareSolverr/FlareSolverr/releases/latest` (`flaresolverr.ts:22`). Install: pick asset whose name contains `windows_x64`, download to `<dir>/flaresolverr.zip`, extract with `powershell -NoProfile -Command Expand-Archive …`, delete zip (`flaresolverr.ts:82-115`). **No checksum/signature verification** (§6.9). Dir: dev `electron/flaresolverr`, prod `<userData>/flaresolverr` (`flaresolverr.ts:29-40`). Start: `flaresolverr.exe --port 8191`, hidden, stdout suppressed, stderr forwarded to console (`flaresolverr.ts:162-201`). Health: `GET http://localhost:8191/health` (5 s timeout), poll every 1 s until ready (`flaresolverr.ts:139-160`). Status mapping: `ready` / `starting` (proc alive **or** exe exists) / `failed` (restarts exhausted) / `off` (exe missing) (`flaresolverr.ts:243-251`). Manual restart resets the counter and waits for readiness (`flaresolverr.ts:262-295`).

### 4.6 Plugin updater (`electron/plugin-updater.ts`)

`https://api.github.com/repos/qbittorrent/search-plugins/contents/nova3/engines` with headers `User-Agent: TorDownloader-PRO/1.0`, `Accept: application/vnd.github.v3+json` (`plugin-updater.ts:16,65-88`). Version regex `/#\s*VERSION\s*:\s*([\d.]+)/i` searched in the **first 10 lines** (`plugin-updater.ts:18,52-63`). Local version = user plugin if present else bundled (`plugin-updater.ts:130-134`). `needsUpdate = local && remote && compare(remote, local) > 0` (`plugin-updater.ts:149-150`); comparison is numeric dotted (`plugin-updater.ts:209-218`). Update downloads each changed `download_url` and writes it to `<userData>/plugins/engines/<name>.py` (`plugin-updater.ts:163-205`). No pin, hash or signature — see §6.10.

### 4.7 Auto-updater (`electron/updater.ts` + `main.ts`)

`electron-updater` with GitHub publish (`electron-builder.yml:5-8`). Configuration: `autoDownload=false`, `autoInstallOnAppQuit=false`, `allowDowngrade=false`, `allowPrerelease=false` (`updater.ts:21-24`). Skipped entirely in dev (`VITE_DEV_SERVER_URL`) (`updater.ts:17,65,93`). Automatic check deferred **3 000 ms** after window creation so renderer listeners exist (`electron/main.ts:122-124`). Circuit breaker: **3** consecutive failures → **6 h** cooldown (`updater.ts:5-10,52-60,82-88`); dismiss writes `last_update_prompt` ISO timestamp and suppresses auto-checks for **24 h** (`updater.ts:10,74-80,121-125`); manual check bypasses both (`updater.ts:91-103`). Install: `quitAndInstall(false, true)` (NSIS) (`updater.ts:116-119`). `isUpdateDownloaded()` is dead code (`updater.ts:127-129`, no callers).

### 4.8 TMDB provider (`electron/tmdb-provider.py`)

Base `https://api.themoviedb.org/3`, images `https://image.tmdb.org/t/p`, `language=es-ES` hard-coded on every call, UA `TorDownloader-PRO/1.0`, request timeout **15 s** (`tmdb-provider.py:21-46`). Key comes from env `TMDB_API_KEY`; missing key → `{"error":"TMDB_API_KEY not set"}` + exit 1 (`tmdb-provider.py:21,162-164`). Parent spawn: `timeout: 30_000`, env `{...process.env, TMDB_API_KEY}` (`electron/main.ts:782-786`).

| Subcommand | TMDB endpoints | Output shape |
|---|---|---|
| `lists` | `/trending/movie/week`, `/movie/popular`, `/movie/top_rated`, `/trending/tv/week`, `/tv/popular`, `/tv/top_rated` | `{movies:{trending,popular,top_rated}, tv:{…}}`, ≤**20** items per list (`tmdb-provider.py:75-104`) |
| `detail <id> movie` | `/movie/{id}`, `/movie/{id}/external_ids` | item + `imdb_id`, `runtime`, `genres[]` (`tmdb-provider.py:107-114`) |
| `detail <id> tv` | `/tv/{id}`, `/tv/{id}/external_ids` | item + `imdb_id`, `seasons:[{season_number,name,episode_count,poster}]` (season 0 filtered out), `genres[]` (`tmdb-provider.py:115-132`) |
| `season <id> <n>` | `/tv/{id}/season/{n}` | `{season_number, episodes:[{episode_number,name,overview,still,air_date}]}` (`tmdb-provider.py:135-146`) |
| `search <q>` | `/search/multi?query&page=1` | array of items filtered to `movie`/`tv` (`tmdb-provider.py:149-158`) |

`format_item`: `{id, title (title||name), overview, poster (w342), backdrop (w780), year (4 chars), rating (round 1), media_type}` (`tmdb-provider.py:61-72`). All output is a single JSON line on stdout; errors are `{"error": "…"}` + exit 1 (`tmdb-provider.py:161-189`). `tmdb-validate` bypasses Python: direct HTTPS `GET /3/configuration?api_key=…` with 10 s timeout and a 10 s JS `setTimeout` → `{success:true}` when `data.images` exists (`electron/main.ts:951-976`).

### 4.9 Latino providers (`electron/latino-providers.py`)

Usage: `latino-providers.py [--stream] <imdb_id> <movie|series> [season] [episode]`; series requires season+episode (`latino-providers.py:6-18,509-525`).

Providers: **TCL** (`https://hacktorrent.to`, WP REST `/wp-json/wpreact/v1`) (`latino-providers.py:32-33`); **Cinecalidad** via Torrentio `https://torrentio.strem.fun/providers=cinecalidad` (`:35`); **Comet** via `https://comet.stremio.ru/<hard-coded config>` (`:37-50`). Metadata resolution chain: IMDB suggestion `https://v3.sg.media-imdb.com/suggestion/<id>.json` (6 s) → Cinemeta `https://v3-cinemeta.strem.io/meta/{movie|series}/<id>.json` (6 s) → Trakt aliases `https://api.trakt.tv/{movies|shows}/<id>/aliases` (5 s) (`latino-providers.py:52-54,138-200`). Magnet building adds 4 trackers (`latino-providers.py:58-63,106-112`).

TCL specifics: search `GET /wp-json/wpreact/v1/search?query&type&page=1` (10 s), detail `/movie/<slug>`, `/serie/<slug>`, `/anime/<slug>`, related `/serie/<slug>/related?vb=12` (8 s); title matching via accent-folded `normalize_title`; movie year must match; `acortalink.net` short links are decoded from base64 `i` param then magnet-scraped (`latino-providers.py:205-368`). Cinecalidad parses Stremio stream `title`/`behaviorHints.filename`, filters provider == `cinecalidad`, size from `💾` regex (`:373-415`). Comet supports both infoHash torrents and direct `url` (debrid-cached) streams; filename from `behaviorHints.filename` or `📄` in description; size from `💾` (`:420-477`).

Result shape for all providers (`format_result`, `:123-133`): `{title, size (or '0 B'), seeders:-1, peers:-1, link, indexer:'TCL'|'CC'|'Comet', info_hash}` — **seeders/peers are always -1** for latino providers (unknown, not real), which the UI hides with `r.seeders >= 0` (`DiscoverResults.tsx:140-141`).

Stream mode lines: `{"type":"provider_start","provider":"TCL"}` for all three, then `{"type":"provider_results","provider":…,"results":[…]}` as each finishes, then `{"type":"done","total":N}` (`latino-providers.py:527-562`). Batch mode prints one JSON array (`:563-565`). Parallelism: 3 workers, `as_completed(timeout=30)`, per-future `timeout=25` (`:482-506`). Parent: `timeout: 45_000`, and a separate JS timer also kills at 45 s; stream lines are forwarded verbatim to `latino-search-progress` (`electron/main.ts:978-1025`); batch parses stdout (`:1031-1053`).

### 4.10 Subtitles (`electron/subtitles.py`)

Source: Stremio OpenSubtitles v3 `https://opensubtitles-v3.strem.io/subtitles/{movie|series}/{imdb[ :season:episode]}.json` (no API key) (`subtitles.py:26,87-109`); IMDB suggestion for title→id (`subtitles.py:27,53-82`). Constants: `MAX_BYTES = 10*1024*1024`, `TIMEOUT = 15`, languages `('spa','en')` (`subtitles.py:31-34`).

CLI: `scan` or `fetch --imdb ttXXXX | --title … [--year …] --type movie|series [--season N --episode N] --dest <video path>` (`subtitles.py:272-307`). Naming: `<video base>.<lang><ext>` with `spa`/`en` (Jellyfin style); language preference tries `spl` (Spanish Latin) then `spa`, and `eng` then `en` (`subtitles.py:146-173`). Validation before write: size ≤10 MB, non-empty, reject HTML prefixes `<!doctype html`, `<html`, `<head`, `<body`; writes `dest.part` then `os.replace` (`subtitles.py:112-135`).

Worker integration: `fetch --title <derived> --type movie|series --dest <video>` plus `--season/--episode` only when the torrent name matched `/[sS](\d{1,2})[eE](\d{1,2})/` (`electron/worker.ts:842-864`). One `spawn` per video file, 60 s timer, env `PYTHONIOENCODING=utf-8`, last output line logged (`worker.ts:865-873`). Errors never fail the download (`worker.ts:875-877`). `scan` mode references a hard-coded Linux path `/srv/storage/arr-data/media` (`subtitles.py:28`) and is **not invoked by the app** (only `fetch` is).

### 4.11 Season cache

`electron/season-cache.ts` — see §3.8. IPC: `get-season-cache` / `save-season-cache` (`electron/main.ts:196-204`). Consumer: `SeasonDownloadModal` skips provider resolution when a cached candidate list exists (`SeasonDownloadModal.tsx:107-124,157-161`).

### 4.12 Stremio catalog / Comet (`electron/main.ts:1065-1163`)

- `CATALOG_BASE` is a capability-configured addon URL: `https://comet.stremio.ru/s/WuPvYs_…/<base64 config>` (`electron/main.ts:1067`). **LEAD MODIFICATION (deliberate):** this replaces the shipped value `https://btttr.cc/pVjfb-…` ("BetterPosters" catalog addon) which is still visible at `git show HEAD:electron/main.ts` line 1057. The two `comet-manifest`/`comet-streams` IPC handlers are **also new** (0 in HEAD, 2 in working tree); everything else in this file is shipped behavior.
- `catalogFetch(path)`: `https.get(CATALOG_BASE+path)`, UA `TorDownloader-PRO/1.0`, 15 000 ms JS timeout, JSON parsed or `null` (`electron/main.ts:1069-1082`).
- `comet-streams` validation: `type` must be `movie|series`; `imdbId` must match `/^tt\d{5,10}$/`; series requires integer season/episode ≥1 and builds id `${imdbId}:${s}:${e}` (`electron/main.ts:1113-1129`).
- `catalog-manifest` caches the manifest's `catalogs` (Comet exposes none → `[]`), `catalog-items` → `data.metas`, `catalog-meta` → `data.meta` (`electron/main.ts:1136-1163`).
- The latino Comet provider uses a **different** hard-coded config for the same host (`latino-providers.py:37-50`), tuned for `la` audio only.

---

## 5. Download semantics (port-critical)

### 5.1 Pipeline

```
cloud transfer (TorBox / RD)
  │  worker poll every 10 s (worker.ts:145-166)
  ▼
downloads row (cloud status/progress) ──► UI DownloadCard
  │  cloud done + local pending/queued → local_status='queued' (worker.ts:198-204,284-290)
  ▼
local queue (FIFO, max 3 active) (worker.ts:20-55)
  ▼
runTorboxDownload / runRealdebridDownload
  ├─ resolveDestination (worker.ts:305-315)
  │    media → computeDestination → <root>/<Title (Year)>[/Season NN]
  │    other → destination_folder (flat, keeps torrent subfolders)
  ├─ per file/link loop (worker.ts:351-486 torbox, 547-675 rd)
  │    ├─ obtain fresh signed URL (TorBox requestdl w/ 3 link attempts; RD unrestrict)
  │    ├─ HARD GUARD isDebridCDN(url) (worker.ts:391-395,584-588)
  │    ├─ new Downloader(url, path, expectedSize).start(onProgress)
  │    ├─ truncated/200/416/cancel handling (§5.2)
  │    └─ size verification (§5.3 step 8)
  ├─ all files ok → local_status='completed', 100 %, speed 0 (worker.ts:488-493,682-687)
  ├─ media → subtitles fetch on raw filenames (worker.ts:497-499,690-692)
  └─ series → Jellyfin rename of own files + sidecars (worker.ts:500-502,693-695)
```

### 5.2 `electron/downloader.ts` — single-file resumable downloader

#### CDN allowlist (hard guard)

```ts
const TORBOX_CDN_DOMAINS = ['dl.torbox.app','cdn.torbox.app','torbox.app','media.torbox.app','tb-cdn.io','tb-cdn.cx']   // downloader.ts:6-13
const REALDEBRID_CDN_DOMAINS = ['real-debrid.com','rd.nu']                                                              // downloader.ts:19-22
const ALL_CDN_DOMAINS = [...TORBOX_CDN_DOMAINS, ...REALDEBRID_CDN_DOMAINS]                                              // downloader.ts:24

function isDebridCDN(url) {                                                                                             // downloader.ts:26-33
  hostname = new URL(url).hostname.toLowerCase()
  return ALL_CDN_DOMAINS.some(d => hostname === d || hostname.endsWith('.' + d))
}
```

The check is applied in the constructor (`downloader.ts:60-67`) and re-applied by the worker before constructing each Downloader (`worker.ts:391-395` for TorBox, `worker.ts:584-588` for RD). Rejection message: `Security: Download rejected. URL ${url} is not a recognized debrid CDN domain. This app only downloads via debrid services (TorBox / Real-Debrid) — never via torrents/seeds/P2P.` (`downloader.ts:62-67`).

**Redirect gap (Lead-requested):** only the URL handed to `new Downloader(...)` is validated. The request at `downloader.ts:102-109` sets **no** `maxRedirects`, `beforeRedirect`, or `follow-redirects` option, and grep shows no `maxRedirects` anywhere in the repository. Therefore the library default applies (installed `follow-redirects` default is `maxRedirects: 21`, `node_modules/follow-redirects/index.js:516`), each hop is followed automatically, and the **final response host is never re-validated** — neither in `downloader.ts` nor in the worker. A debrid URL that 3xx-redirects off-allowlist would still be downloaded. (Debrid links normally 302 to their own CDN, which is why this has not been observed; the guard is nevertheless bypassable by redirect.)

Also note the allowlist is host-suffix based: `evil-torbox.app` does **not** pass (`===` or `.`-prefixed suffix), but `foo.tb-cdn.io` does, and any DNS-level compromise of those domains defeats it.

#### Algorithm (exact order)

1. **Guard** (constructor, `downloader.ts:60-67`): throw if not allowlisted.
2. **Resume decision** (`downloader.ts:85-96`): only when `expectedSize > 0` **and** the destination file exists. `cur = stat.size`:
   - `cur >= expectedSize` → resolve `{success:true, bytes_downloaded:cur}` **without any network request**;
   - `cur > 0` → `startOffset = cur`; `stat` failure → start from 0.
3. **Range header** (`downloader.ts:99-100`): `Range: bytes=<startOffset>-` only when resuming.
4. **Request** (`downloader.ts:102-109`): `GET` stream, `timeout: 60000`, `cancelToken`.
5. **Server ignored Range** (`downloader.ts:111-119`): if `startOffset > 0 && response.status !== 206` → destroy stream, `cancelSource=null`, resolve `{success:false, error:'Range not supported by CDN', bytes_downloaded:startOffset, rangeIgnored:true}`. The partial file is deliberately **kept** (comment says do not truncate/overwrite; the caller decides).
6. **Sizing** (`downloader.ts:121-122`): `totalLength = expectedSize || parseInt(content-length) || 0`. Writer opens with `flags:'a'` when resuming, else default (`createWriteStream` truncates) (`downloader.ts:128`).
7. **Progress** (`downloader.ts:130-149`): accumulate `bytesDownloaded` (starts at `effectiveOffset`); throttle callback to at most once per **1000 ms**; `speed = (bytes - lastBytes) / ((now-lastUpdate)/1000)` bytes/s; `progress_percent = totalLength ? min(100, bytes/totalLength*100) : 0`; payload `{bytes_done, progress_percent, speed, total_bytes}`.
8. **Finish** (`downloader.ts:153-169`): on stream end, if `expectedSize > 0 && bytesDownloaded < expectedSize` → `{success:false, error:'Truncated download: got X of Y bytes'}` (partial file kept so the worker can resume). Otherwise success.
9. **Write error** (`downloader.ts:171-175`): `{success:false, error:err.message}` with the partial kept.
10. **Catch** (`downloader.ts:176-190`):
    - `axios.isCancel` → `{success:false, error:'Cancelled', bytes_downloaded:startOffset}`;
    - HTTP **416** **and** resuming (`startOffset>0`) → `{success:true, bytes_downloaded: fileSizeOnDisk}` — "range beyond EOF means the partial file is complete";
    - otherwise `{success:false, error:err.message, bytes_downloaded:startOffset}`.
11. **abort()** (`downloader.ts:70-76`): cancels the axios token, sets it `null`; safe at any time.

`DownloadResult` = `{success, error?, bytes_downloaded, rangeIgnored?}`; `rangeIgnored` is documented as "resume requested but server ignored Range" (`downloader.ts:44-50`). There is no checksum, no ETag/If-Range, no multi-connection, no retry inside this class — retries live in the worker (§5.3).

### 5.3 `electron/worker.ts` — TorBox local download (exact)

1. **Destination** (`worker.ts:317-324`): `rec = getDownloadByTorboxId(tid)`; `type = rec.type`; `resolveDestination(settings, dlData.name, type, rec.season)`; `safeMkdirSync(destFolder)`. `resolveDestination` (`worker.ts:305-315`) routes `movie|series` to the Jellyfin layout and everything else to `settings.destination_folder` (throws `Destination folder is not configured` if blank).
2. **File list** (`worker.ts:326-343`): prefer `dlData.files`; if empty call `tb.getTorrentInfo(tid)` and take `data[0].files` (array) or `data.files` (object). Filter entries with non-null `id`. **`totalFiles === 0` throws `TorBox returned no downloadable files`.**
3. **Per file** (`worker.ts:351-486`): `fileName = fileInfo.name || fileInfo.path || 'file_i'`; `expectedSize = getFileSize(fileInfo)` where `getFileSize` tries `size`, `bytes`, `filesize` with `parseInt` (`worker.ts:94-100`, so byte strings would be mis-parsed — TorBox returns numbers). Name/path construction in §5.10.
4. **Link retry loop** (`worker.ts:373-386`): outer `attempt = 1..3` (size/link attempt), inner `l = 1..3` for `tb.getDownloadLink(tid, String(fileId))` with backoff `l*2000 ms`; if all inner fail: on outer attempts 1-2 sleep `attempt*2000` and continue; on attempt 3 throw `TorBox did not return a download link for <file>: <reason>`.
5. **CDN guard** (`worker.ts:390-395`): throw `Security: Download URL … is not from a recognized debrid CDN.` (this string can contain the full signed URL, §6.4).
6. **Progress continuity** (`worker.ts:397-408`): before starting, read the partial file size and set `local_progress = floor(partial/expectedSize*100)` (or `floor(idx/totalFiles*100)` when size unknown), `local_status = 'Downloading i/n'` + ` (intento k)` when retrying, `local_path = destFolder`, then emit `downloads-updated`.
7. **Progress callback** (`worker.ts:410-434`): DB write at most every **2000 ms**; `overall = totalBytes>0 ? (completedBytes+bytes_done)/totalBytes*100 : (idx/totalFiles)*100 + progress_percent/totalFiles`; `local_progress = clamp(overall, 0, 99)`; `local_speed = floor(speed)`; `local_eta = formatETA(remaining, speed)` with `remaining = totalBytes - (completedBytes+bytes_done)`.
8. **Result handling** (`worker.ts:436-471`):
   - `!success && rangeIgnored`: if the on-disk file is non-empty → log `CDN ignores resume — accepting existing file` and set `verified = true` (no size check!); if 0 bytes → fall through to retry.
   - `!success` and `attempt < 3` → log, sleep `attempt*2000`, **retry with a fresh link** (Downloader resumes the partial).
   - `!success` on attempt 3 → throw `Failed to download <file>: <error>`.
   - Success + `expectedSize > 0`: `actual === expected` → verified; else `actual > 0 && attempt >= 2` → **accept anyway** (comment: debrid metadata size may be off or CDN stopped delivering); else log partial, sleep `attempt*2000`, retry.
   - Success + `expectedSize === 0` → verified (no size check).
   - After the 3-attempt loop without `verified` → throw `Failed to verify <file>: expected N bytes, got M` (if partial) or `Download failed for <file>: no data received (N bytes expected)` (`worker.ts:473-485`).
9. **Accounting** (`worker.ts:473-475`): `completedBytes += expectedSize || actualSizeOnDisk`; `downloadedFiles.push(safeRelPath)` (own files for rename/subtitles).
10. **Completion** (`worker.ts:488-493`): `local_status='completed'`, `local_progress=100`, `local_speed=0`, `local_path=destFolder`.
11. **Subtitles, then rename** (`worker.ts:495-502`): for `movie|series` call `fetchSubtitlesForDownload(dlData.name, type, destFolder, downloadedFiles)`; for `series` call `renameForJellyfin(dlData.name, rec, destFolder, downloadedFiles)`. Subtitles first is deliberate: the sidecars are named from the **raw** video base while it still exists; the rename then moves video+sidecars together (`worker.ts:495-496,718-724`).
12. **Failure** (`worker.ts:504-514`): any throw → `local_status = 'failed: <err.message>'`, `local_speed=0`; `finally` always clears the active maps, dispatches the next queued download and emits `downloads-updated`.

### 5.4 `electron/worker.ts` — Real-Debrid local download

1. Destination: `resolveDestination(settings, dlData.filename || dlData.name, type, rec.season)` (`worker.ts:519-526`).
2. `rd.getTorrentInfo(tid)` → `links: string[]`, `totalBytes = torrentInfo.bytes || torrentInfo.original_bytes || 0`; empty links → throw `Real-Debrid returned no download links for this torrent` (`worker.ts:528-540`).
3. Per link (`worker.ts:547-675`), attempt loop `1..3`:
   - `rd.unrestrictLink(link)` fails: on attempts 1-2 sleep `attempt*2000` continue; on 3 **`break`** with the warning `Skipping link … unrestrict failed` (`worker.ts:556-564`).
   - `{download: directUrl, filename, filesize}`; missing `directUrl` → retry or throw after 3 (`worker.ts:566-570`).
   - `expectedSize = filesize || 0`; flatten name for media / keep path otherwise (§5.10).
   - CDN guard → throw on non-allowlisted `directUrl` (`worker.ts:583-588`).
   - Progress/DB writes exactly as TorBox but throttled at **1000 ms** (`worker.ts:602-626`).
   - Result handling identical to TorBox (`rangeIgnored` accept, retries, size accept at `attempt>=2`, throw at 3) (`worker.ts:628-660`).
   - `verified` false after the loop → throw `Failed to verify part …` / `Download failed for part …` (`worker.ts:662-674`).
4. `completedBytes === 0 && links.length > 0` → throw `All N link(s) failed to unrestrict or download` (`worker.ts:677-680`) — only reachable if every link `break`s **without** throwing, which the previous step prevents; effectively dead unless `verified` is set by `rangeIgnored` on a 0-byte file (impossible, `sz>0` guard).
5. Completion + subtitles + rename: same as TorBox (`worker.ts:682-695`).
6. Failure → `failed: <msg>` (`worker.ts:697-706`).

**Contradiction to flag:** the comment at `worker.ts:561-562` says dead/expired hoster links are "skipped — don't fail the whole torrent", but the `break` exits the attempt loop and the following `else` branch (`worker.ts:665-674`) throws, failing the transfer. The behaviour is "first dead link fails the torrent". §9.

### 5.5 Queue, cancel, auto-remove, startup resume

- **Concurrency** (`worker.ts:16-55`): `MAX_CONCURRENT_DOWNLOADS = 3`; FIFO array `localDownloadQueue`; `activeLocalDownloads` map is the slot counter; `enqueueLocalDownload` is idempotent per id (in-flight or queued); `dispatchNextLocalDownload` fills slots while `size < 3`.
- **Queue entry condition**: cloud done **and** local status ∈ `{pending, queued}` **and** not active/queued (`worker.ts:198-204,284-290`); sets `local_status='queued'` before enqueueing.
- **Cancel** (`worker.ts:57-81`): `cancelLocalDownload(tid)` aborts the Downloader, drops it from active maps, or removes it from the waiting queue; returns whether anything was aborted. IPC `cancel-download` (`electron/main.ts:503-526`) then deletes the DB row **immediately** (before cloud delete), emits `downloads-updated`, and fire-and-forgets the cloud delete (`RD deleteTorrent`, TorBox `controlTorrent(id,'Delete')`) — explicitly so the stop button is instant (`main.ts:510-512`).
- **Auto-remove** (`worker.ts:206-209,292-295`): when `settings.auto_remove_completed`, cloud is done **and** `local_status==='completed'` → delete cloud torrent then delete DB row (inside the poll; failures bubble to the poll catch).
- **Startup resume** (`worker.ts:128-143`): described in §3.7.
- **Worker loop** (`worker.ts:145-166`): every **10 000 ms**; polls TorBox if `torbox_token`, then RD if `realdebrid_token`, then emits `downloads-updated`. Errors are logged and swallowed; the interval keeps running.
- **Partial-file policy everywhere:** every failure keeps the `.part`-style partial (there is no `.part` extension — the real filename is used) so the next attempt resumes via Range (§5.2 step 2).

### 5.6 Speed / ETA / progress math

| Quantity | Formula | Evidence |
|---|---|---|
| speed (bytes/s) | `(bytesDownloaded - lastBytes) / ((now - lastUpdate)/1000)`, sampled ≥1 s apart | `downloader.ts:133-137` |
| file percent | `totalLength ? min(100, bytes/totalLength*100) : 0` | `downloader.ts:140` |
| overall percent | `totalBytes>0 ? floor((completedBytes + bytes_done)/totalBytes*100) : floor(idx/total*100 + progress_percent/total)` | `worker.ts:415-420,607-612` |
| persisted percent | `clamp(overall, 0, 99)` while downloading; **100 only on completion** | `worker.ts:427,619,490` |
| remaining bytes | `totalBytes>0 ? totalBytes - (completedBytes + bytes_done) : 0` | `worker.ts:422,614` |
| ETA | `formatETA(remaining, speed)`; `''` if `speed<=0 || remaining<=0`; `<60 s` → `"{s}s"`; `<3600` → `"{m}m {s}s"` (`s = s%60`, not padded); else `"{h}h {m}m"` (seconds dropped) | `worker.ts:102-108` |
| DB write cadence | TorBox **2000 ms**, RD **1000 ms**; renderer event after every DB write | `worker.ts:410-434,602-626` |
| UI speed format | `>1 GiB/s` → `GB/s` 2 decimals; else `MB/s` 2 decimals; `0` → `'0 MB/s'` | `DownloadCard.tsx:28-32` |

### 5.7 `electron/media-layout.ts` — release parsing + destination (must be 1:1)

#### Constants (quote these exactly)

```ts
// TAG_RE — case-insensitive, NO global flag on purpose (test() in a loop would keep lastIndex)
new RegExp(['1080p','720p','2160p','4k','480p','576p','hdtv','webrip','web-dl','webdl','web',
 'bluray','blu-ray','brrip','h264','h265','x264','x265','hevc','avc','aac','ac3','dts','ddp5',
 'ddp5.1','dd5','dd5.1','atmos','truehd','amzn','atvp','nf','hbo','amazon','itunes','vff','vostfr',
 'multi','dual','lat','latino','spanish','english','dl','extreme','weeds','cakes','megusta','ethel',
 'grace','ntb','tgx','rarbg','eztv','rartv','ion10','glhf','rmteam','playweb','ditr','skst','dkv',
 'dirt','msd','xvid','proper','repack','remux','internal','imax','muxed','opus','flac','aac2',
 '2.0','5.1','7.1'].join('|'), 'i')                                    // media-layout.ts:17-30

const SEASON_EP_RE = /\bS(\d{1,2})[Ee](\d{1,3})\b/                        // media-layout.ts:32
const TEMP_EP_RE   = /\bT(\d{1,2})[Ee](\d{1,3})\b/i  // latino "T2E1"    // media-layout.ts:33
const YEAR_RE      = /\b(19\d{2}|20\d{2})\b/g        // 'g' for matchAll // media-layout.ts:34
const PAREN_YEAR_RE= /[\[\(]\s*(19\d{2}|20\d{2})\s*[\]\)]/                // media-layout.ts:35
const GROUP_RE     = /^[A-Z][A-Z0-9]{3,7}$/                               // media-layout.ts:37
cleanToken(t) = t.replace(/[._\-\[\](){}]/g,' ').replace(/\s+/g,' ').trim() // media-layout.ts:39-44
```

`TAG_RE` has **no word boundaries**: `TAG_RE.test(token)` matches substrings, so a title token containing a tag as a substring is dropped (e.g. `Webster` matches `web`; `Everyone` does not match, but `Dirt` matches `dirt`). This is shipped behaviour and must be replicated if parity is required, including its false positives.

#### `parseReleaseName(name)` — exact steps (`media-layout.ts:46-109`)

1. `season`: first match of `SEASON_EP_RE` **or** `TEMP_EP_RE` → `parseInt(m[1],10)`.
2. `year`: collect all `YEAR_RE` matches. If a season/episode match exists, keep only matches whose `index > seMatch.index`; if that subset is empty use all matches; **take the last** match's value. (Comment: a leading 4-digit token like "1923"/"1883" is the title, not the year.)
3. `cutIdx`: if season match → its `index`; else the first year match with `index > 0`. `titlePart = cutIdx>0 ? raw.slice(0,cutIdx) : raw`.
4. Strip `PAREN_YEAR_RE` from `titlePart` (only years actually inside `()`/`[]`).
5. Tokenize `titlePart` with `/[._\-\[\](){} ]+/`, map `cleanToken`, drop empties.
6. `startsWithNumericTitle = /^\d{4}/.test(raw.trim())`.
7. For each token index `i`:
   - if `TAG_RE.test(t)` → skip;
   - if `i===0 && startsWithNumericTitle && /^\d{4}$/.test(t)` → keep (show name like "1923");
   - if `t.replace(/\d+$/,'').length <= 1` → skip (numeric/single-char noise, but "La"/"de"/"of" survive because they have no trailing digits and length ≥2);
   - else keep.
8. While `titleTokens.length > 1` and the last token matches `GROUP_RE` → pop it (release group).
9. `title = titleTokens.join(' ') || cleanToken(raw).slice(0,60) || 'Unknown'`; return `{title, year, season}`.

Worked edge cases (tests are the executable spec, `src/__tests__/media-layout.test.ts:4-38`):

| Input | Output |
|---|---|
| `Breaking.Bad.2008.S01E02.1080p.WEB-DL.x264-GROUP` | title `Breaking Bad`, year 2008, season 1 |
| `Inception.2010.1080p.BluRay.x264-AMIABLE` | title `Inception`, year 2010 |
| `La Casa de Papel 2017 1080p LATINO` | title `La Casa de Papel`, year 2017, season undefined |
| `1923.S02e01.2025.1080P-Dual-Lat.mkv` | title `1923`, year 2025, season 2 |
| `1883.S01E03.2021.…` | title `1883`, year 2021, season 1 |

Known parser limitations (not bugs to "fix" during the port unless explicitly re-scoped): no support for `1x02`/`Season 1`/`Temporada 1`/anime absolute numbering; `year` can be a resolution-adjacent false positive if the title contains a bare 4-digit token after the first position; the title is force-truncated to 60 chars only in the fallback path.

#### TMDB resolve (best effort, memoized)

`tmdbResolve(parsed, type, apiKey)` (`media-layout.ts:141-170`): returns `null` when `apiKey` is empty. Cache key `` `${type}|${parsed.title.toLowerCase()}` ``, TTL **15 min** (`TMDB_CACHE_TTL = 15*60*1000`, line 139), cached even when the value is `null`; when `cache.size > 500` stale entries are pruned first (lines 163-167). Request: `GET https://api.themoviedb.org/3/search/{movie|tv}?api_key=<key>&query=<encoded parsed title>&language=es-ES&include_adult=false`, UA `TorDownloader-PRO/1.0`, timeout **6000 ms**, response JSON parsed defensively (`tmdbGet`, lines 111-130). Picks `results[0]`; canonical title `first.title || first.name || parsed.title`; year = first 4 digits of `release_date || first_air_date`.

#### `computeDestination(settings, torrentName, type, seasonOverride?)` (`media-layout.ts:183-224`)

1. `kind = type === 'series' ? 'series' : 'movie'` (so `''` behaves as movie).
2. `parsed = parseReleaseName(torrentName)`; `meta = await tmdbResolve(parsed, kind, settings.tmdb_api_key)` (exceptions → `null`).
3. `title = meta?.title || parsed.title`; `year = meta?.year || parsed.year`; `titleFolder = year ? "<title> (<year>)" : "<title>"`.
4. `root = kind==='series' ? (series_folder || destination_folder) : (movies_folder || destination_folder)`; empty → throw `No destination folder configured (set movies/series folders in Settings)`.
5. Movie: `folder = titleFolder`; `season: undefined`.
6. Series: `seasonOnly = /\bS(\d{1,2})\b/.exec(torrentName)`; `season = seasonOverride ?? parsed.season ?? seasonOnly ?? 1`; `folder = "<titleFolder>/Season <pad2(season)>"`.

Series layout tests (`media-layout.test.ts:40-85`): DB override wins over name (`S02E05` + override 3 → `Season 03`); bare `S01` season-pack marker works; nothing parseable → `Season 01`; numeric show `1923` keeps name and uses the release year.

#### `safeSegment(value)` (`media-layout.ts:227-235`)

`value.replace(/[<>:"/\\|?*\x00-\x1f]/g,'_').trim().replace(/^\.+/,'').replace(/\.+$/,'') || 'Unknown'`. Applied per path segment when joining the computed folder onto `root` (`worker.ts:309`; `main.ts:252`). Note the worker also has a near-duplicate `getSafePathPart` (`worker.ts:83-86`) used for file names — same character class, but it strips leading **and** trailing dots with one regex.

#### Determinism (Lead-requested confirmation)

`parseReleaseName` and `safeSegment` are **pure and platform/locale independent**: no `Intl`, no `toLocaleLowerCase`, no `Date`, no filesystem, no `process`; only `String.prototype.toLowerCase()` (Unicode default case mapping, not locale-sensitive), ASCII regex literals, `parseInt`, and `matchAll`. Same input → same output on Kotlin/JVM provided the regex engine semantics for `\b`, `\d`, and case-insensitive matching are honored (Kotlin `Regex` = java.util.regex: `\b` is ASCII word boundary by default, `\d` ASCII by default unless `UNICODE_CHARACTER_CLASS`, `CASE_INSENSITIVE` ASCII-only unless `UNICODE_CASE`; keep both defaults off). `computeDestination` is deterministic **given** `settings`, `torrentName`, `type`, `seasonOverride` **only when `tmdb_api_key` is empty or the TMDB cache has no effect**; with a key it performs network I/O and is time-dependent through the 15-minute cache. Recommendation: port `parseReleaseName`/`safeSegment`/`computeDestination`'s pure part verbatim and inject the TMDB resolver as a suspend function with the same cache semantics.

### 5.8 `renameForJellyfin` — series rename (`electron/worker.ts:725-802`)

Inputs: torrent `name`, DB record `{season, episode}`, `destFolder`, `ownFiles` (raw relative names this transfer downloaded).

1. `videos = readdir(destFolder)` filtered by `VIDEO_EXT_RE = /\.(mkv|mp4|avi|m4v|mov|wmv)$/i` and not starting with `.`, **sorted by size descending** (line 727-729).
2. If `ownFiles` non-empty: keep only videos whose lowercase name is in the own set (lines 730-733). Rationale comment (lines 718-724): several season transfers share one folder; renaming "all videos in the folder" once renamed a file another transfer was still downloading, which made the other transfer's size verification read 0 bytes and restart the download.
3. If no videos → return. `torrentEp = name.match(EP_RE)` with `EP_RE = /[sS](\d{1,2})[eE](\d{1,2})/` (line 713); `title = parseReleaseName(name).title`.
4. For each video `v`:
   - `fileEp = v.match(EP_RE)`;
   - `season = fileEp ? +fileEp[1] : (rec.season ?? (torrentEp ? +torrentEp[1] : undefined))`;
   - `episode` analogous (lines 768-771). Precedence: **file's own SxxEyy > DB record > torrent name**.
   - no season or no episode → leave the original name (continue).
   - `ext = path.extname`; `code = "S" + pad2(season) + "E" + pad2(episode)`; `newBase = "<title>.<code>"`; `newPath = <destFolder>/<newBase><ext>`; e.g. `Breaking Bad.2008.S01E02.1080p.WEB-DL.x264-GROUP` → `Breaking Bad.2008S01E02`? **No** — `title` is the *parsed* title (`Breaking Bad`), so the result is `Breaking Bad.S01E02.<ext>`.
   - if `newPath === target` → continue (already named);
   - if `newPath` exists → this download is a duplicate: delete the freshly downloaded video **and its sidecars** (`deleteSubs(rawBase)`), log `already exists as … — removed duplicate` (lines 780-793);
   - else `rename(video)` then `moveSubs(rawBase, newBase)` (lines 794-796).
5. `moveSubs(srcBase,dstBase)` (lines 740-754): for every file in the folder matching `SUB_EXT_RE = /\.(srt|vtt|ass|ssa|sub)$/i` and starting with `srcBase + '.'` or `srcBase + '_'`, rename to `dstBase + remainder`; if the destination sidecar already exists, **delete the source** (dedupe) instead of overwriting.
6. All errors are logged and swallowed (`rename skip: …`); a rename failure never fails the download.

Consequences to reproduce: only series are renamed (movies keep the release filename); the metadata title comes from the release name, **not** from TMDB; punctuation/umlauts in the parsed title survive (the file is not passed through `safeSegment` — only folder segments are); `ownFiles` scoping is mandatory for correctness with parallel season episodes.

### 5.9 Subtitle fetch order and title derivation (`electron/worker.ts:710-878`)

1. Order: **subtitles first, rename second** (lines 495-502, 689-695). The subtitle CLI derives its output base from the video file it is given, so it must run before the video is renamed; `moveSubs` then drags the sidecars along.
2. Command resolution (`subtitleCommand`, lines 804-818): dev → `python <repo>/electron/subtitles.py`; prod → `<resources>/subtitles.exe`, else `python <resources>/subtitles.py`; missing → skip with log. Per-file timeout **60 000 ms**, `PYTHONIOENCODING=utf-8`, output last line logged (lines 865-873).
3. Video selection: same own-files-scoped, size-desc sort as the rename (lines 833-839).
4. Title derivation (lines 843-859), independent of `parseReleaseName`:
   - `epMatch = name.match(EP_RE)`; `isSeries = type==='series' || !!epMatch`;
   - strip years `/\b(19|20)\d{2}\b/g`, the episode marker, replace `[.\-_]+` with space, then strip this tag list (case-insensitive, word-bounded):
     `1080p|720p|4k|2160p|web[- ]?dl|bluray|webrip|hdr|x264|x265|h\.?265|hevc|h\.?264|aac|ac3|ddp5[.\s]?1|atmos|5[.\s]?1|7[.\s]?1|dual|latino|english|spanish|extended|unrated|repack|proper|nf|amzn|atvp|itunes|hmax|dsnp|uhd|remux|s\d{1,2}|complete|season|series|episode` (line 849);
   - collapse spaces; while >3 tokens drop a trailing `^[A-Z0-9]{2,}$` token that is not all-lowercase (release group); empty → use the full torrent name.
5. Args: `fetch --title <title> --type <movie|series> --dest <video absolute path>` plus `--season <epMatch[1]> --episode <epMatch[2]>` when a series marker exists (lines 861-864).

### 5.10 File path construction

| Function | Rule | Evidence |
|---|---|---|
| `resolveDestination` | movie/series → `path.join(root, ...folder.split('/').map(safeSegment))`; other → `destination_folder` as-is (no sanitization) | `worker.ts:305-315` |
| `getSafePathPart(value)` | `replace(/[<>:"/\\|?*\x00-\x1f]/g,'_').trim().replace(/^\.+|\.+$/g,'') \|\| 'Unknown'` | `worker.ts:83-86` |
| `getSafeRelativePath(value, fallback)` | split on `/` or `\`, sanitize each part, drop empty/`.`/`..`, join; fallback sanitized | `worker.ts:88-92` |
| Media flattening (TorBox) | `getSafePathPart(fileName.split(/[/\\]+/).pop())` — torrent-internal folders are dropped so every episode lands directly in `Season NN` | `worker.ts:355-361` |
| Media flattening (RD) | same on `filename` | `worker.ts:574-579` |
| Non-media layout | full relative path preserved (`getSafeRelativePath`) | `worker.ts:359-361,576-578` |
| Directory creation | recursive `mkdirSync`, ignores `EEXIST`/`EPERM` | `worker.ts:110-121` |

### 5.11 Edge cases that must be reproduced (or explicitly re-decided)

| Case | Shipped behaviour | Evidence |
|---|---|---|
| Partial file already ≥ expected size | Downloader returns success without HTTP; worker then sees `actual !== expected`, retries; on attempt 2 accepts the larger file | `downloader.ts:86-93`; `worker.ts:455-470` |
| CDN ignores Range (200) | accepted if the on-disk file is non-empty — **no size check on this path** | `worker.ts:437-446` |
| HTTP 416 while resuming | treated as complete, bytes = on-disk size | `downloader.ts:182-187` |
| Empty body / dead link (0 bytes) | `Truncated download: got 0 of N bytes` → retried with fresh link → fails after 3 | `downloader.ts:160-165`; `worker.ts:447-452` |
| Expected size unknown (`0`) | no truncation check, no size verification, success after stream end | `downloader.ts:122,160`; `worker.ts:468-470` |
| Metadata size wrong but file non-empty | accepted on attempt ≥ 2 with a warning | `worker.ts:459-463` |
| Server cut at ~97 % | resume via Range on the next attempt, progress seeded from disk | `downloader.ts:82-96`; `worker.ts:400-407` |
| `content-length` 0/chunked | `totalLength=0` → percent stays 0 unless `expectedSize` known | `downloader.ts:122,140` |
| RD link unrestrict failure | comment says skip, code fails the torrent (§5.4) | `worker.ts:556-564,662-674` |
| Same episode added twice | second file renamed onto existing target → duplicate video+subs deleted | `worker.ts:780-793` |
| Two transfers writing the same file concurrently | possible: no lock; both Downloaders open the same path (append or truncate) | `worker.ts:362,579` (no locking) |
| Cancel mid-write | axios cancel → `Cancelled`; partial retained; DB row already deleted | `downloader.ts:180-181`; `main.ts:503-514` |
| Destination folder unset | media → throw `No destination folder configured…`; flat → `Destination folder is not configured` | `media-layout.ts:208-210`; `worker.ts:311-313` |
| Disk full / EPERM on mkdir | mkdir ignores `EPERM`; write errors surface as `failed: <errno message>` | `worker.ts:110-121,171-175` |

---

## 6. Security-relevant behaviour

### 6.1 Electron hardening (what exists)

- `contextIsolation: true`, `nodeIntegration: false` (`electron/main.ts:89-93`); preload whitelist only (`electron/preload.ts:121`).
- `setWindowOpenHandler` denies in-app windows and forwards every `window.open` target to the OS browser (`electron/main.ts:96-99`).
- The CF fetch window also uses `nodeIntegration:false`, `contextIsolation:true`, unique partition (`cf-fetcher.ts:40-44`); the FlareSolverr process is hidden (`flaresolverr.ts:170-173`).
- **No Content-Security-Policy** in `index.html`, no `session.setPermissionRequestHandler`, no `webSecurity:false` (good). Renderer loads only local files in prod, but remote images (TMDB `image.tmdb.org`) and remote provider strings are rendered; without CSP the XSS blast radius is "full `electronAPI`", which includes all secrets (§6.3).
- Renderer never uses `dangerouslySetInnerHTML`; all provider strings are rendered as text (React escaping) — verified by grep: no `dangerouslySetInnerHTML` in `src/`.

### 6.2 Debrid CDN allowlist — the one hard data-boundary guard

Enforcement points: constructor (`downloader.ts:60-67`) and worker pre-checks (`worker.ts:391-395,584-588`); matcher `isDebridCDN` (`downloader.ts:26-33`); domain lists (`downloader.ts:6-22`). The app cannot be made to download from an arbitrary host **as long as the URL it is given is the URL it fetches** — which is not guaranteed:

| Gap | Detail | Evidence |
|---|---|---|
| Redirects | Only the initial URL is checked. `axios({...})` sets no `maxRedirects`/`beforeRedirect`; grep: zero occurrences of `maxRedirects` in the repo. Installed `follow-redirects` default is 21 hops, so the final host is unchecked. | `downloader.ts:102-109`; `node_modules/follow-redirects/index.js:516` |
| RD partner CDNs | RD's allowlist is only `real-debrid.com` and `rd.nu`, while the comment claims "CDN partner domains" (`downloader.ts:15-22`). Legitimate `download.real-debrid.com` passes; a partner host would be **rejected** (false negative = broken download, not a leak). | `downloader.ts:19-22` |
| TorBox `requestdl` | The API call carries the bearer token as a query parameter (`torbox.ts:194`); the returned link is capability-bearing. Any log/error that echoes the link leaks a usable download URL until expiry. | `torbox.ts:190-196`; §6.4 |
| No integrity check | No hash/checksum verification of downloaded files; "verified" means size comparison only. | `worker.ts:455-470` |

**Port requirement:** validate the allowlist on the *final* URL (`response.request.url` / OkHttp interceptor after redirects), keep the initial-URL check too, and add a `maxRedirects` cap (e.g. 3).

### 6.3 Tokens and secrets at rest

`settings` stores, in **plaintext** SQLite (no SQLCipher, no OS keychain, no obfuscation): `torbox_token`, `realdebrid_token`, `realdebrid_refresh_token`, `realdebrid_client_id`, `realdebrid_client_secret`, `tmdb_api_key` (`electron/db.ts:15-19,24`; DDL in §3.2). The DB file lives under `%APPDATA%\tordownloader-pro\tordownloader.db` (`main.ts:24-25`, `db.ts:5`) and is not backup-excluded by the app.

- `get-settings` returns the **whole row including all secrets** to the renderer (`electron/main.ts:155`; `db.ts:109-116`). Every renderer script therefore has all tokens in memory; `AppSettings` in `types/electron.ts:2-9` even declares them as normal fields. Settings UI only displays presence/masked account e-mail (`SettingsModal.tsx:336-346,368-375,23-27`).
- `set-settings` and DB helpers interpolate caller-provided **object keys** into SQL (`UPDATE settings SET ${key} = ?`, `INSERT … (${keys})`, `UPDATE downloads SET ${key} = ?`) without a column allowlist (`db.ts:118-132,142-147,149-164`). With `contextBridge` the caller is trusted app code, but a renderer XSS turns this into arbitrary column writes (and `better-sqlite3` rejects multi-statement strings, so not classic stacked SQL injection). Android equivalent: fixed column names / typed DAO.
- **`realdebrid_refresh_token` has no consumer.** It is read from the token response and persisted (`electron/main.ts:628,636`) and typed (`db.ts:17,52,80`), but `RealDebridAPI.refreshAccessToken()` (`realdebrid.ts:90-113`) is never called from anywhere (grep: single definition, zero call sites). RD access tokens expire; the app does not renew them, so the user must re-link. Android should implement the refresh flow (the credentials needed are already stored).
- Tokens are not written to logs directly (grep for `token` in `console.*` calls: none). Leak paths are indirect — §6.4.
- Tokens transit as `Authorization: Bearer` headers to `api.torbox.app` / `api.real-debrid.com`; TorBox additionally puts the token in the `requestdl` **query string** (`torbox.ts:194`), so proxy logs / crash reports / screenshots of URLs can capture it.

### 6.4 Must-not-log list (what the port must redact)

Root cause on desktop: every `console.*` call is mirrored into an in-memory buffer and streamed to the renderer (`electron/main.ts:41-60`); non-string arguments are `JSON.stringify`-ed (`main.ts:45`). I verified with the repo's installed axios (1.20.0) that `JSON.stringify(new AxiosError(...))` **includes `config.headers.Authorization`** — so logging an `AxiosError` object leaks the bearer token.

| # | Location | What reaches the log/UI/DB | Severity | Evidence |
|---|---|---|---|---|
| 1 | `electron/main.ts:519-522` | `console.warn('RD delete for … failed:', err)` / `console.warn('TorBox delete for … failed:', err)` with a raw axios error → `sendLog` JSON-stringifies it → `config.headers.Authorization` (bearer token) into log buffer, `app-log` event and Logs screen | **critical** | `main.ts:44-46,58-60,519-522`; verified with axios 1.20.0 |
| 2 | `electron/worker.ts:392-394` | thrown `Security: Download URL ${downloadUrl} …` embeds the full signed CDN URL; caught at `worker.ts:504-508` and persisted as `local_status = "failed: …"` → DB + UI + (on startup resume logs, name only) | high (capability URL persisted in plaintext) | `worker.ts:391-395,504-508` |
| 3 | `electron/downloader.ts:62-67` | same full URL in the constructor `throw` → same DB persistence path | high | `downloader.ts:60-67`; `worker.ts:504-508` |
| 4 | `electron/cf-fetcher.ts:60,122,163` | full target URL of every CF-bridged fetch (`[CF Fetcher] Fetching: <url>`, timeout/load-failure messages) | medium (provider URLs may carry session tokens) | `cf-fetcher.ts:34,60,116-124,163` |
| 5 | `electron/flaresolverr.ts:180` | FlareSolverr stderr verbatim (`console.warn('[FlareSolverr]', …)`) — the solver logs target URLs, and challenge HTML snippets can include cookies | medium | `flaresolverr.ts:175-181` |
| 6 | `electron/main.ts:793,839,883,928` | first 500 chars of TMDB sidecar stderr — could include a TMDB URL with `api_key` if Python surfaces it in an exception string (not proven from code; treat as possible) | low/possible | `main.ts:793` etc.; `tmdb-provider.py:161-189` |
| 7 | `electron/main.ts:781,827,871,916` | spawn command lines for TMDB (query text only) | none | `main.ts:781` |
| 8 | `electron/main.ts:1067` | Comet capability URL hard-coded in source (not a user secret, but a shared access token for the addon config) | informational | `main.ts:1067` |
| 9 | `electron/worker.ts:442,462,633,650` | file names and byte counts only | none | – |

Also note **`local_status` itself is free text** and is rendered in the UI (`DownloadCard.tsx:163`), exported nowhere but persisted in SQLite; the port must sanitize it before display/persistence.

Android "must not log" rules: never log `Authorization`, `token`, `api_key`, full request/response URLs for debrid/CDN, `AxiosError`/OkHttp `Response` objects, cookies, or full `local_status` failure strings; have a single `redact(url|headers|throwable)` helper used by the logger; disable verbose logging in release builds.

### 6.5 Local Cloudflare bridge (`cf-fetcher.ts`) — unauthenticated local server

`http.createServer` on `127.0.0.1:<random>` with `Access-Control-Allow-Origin: *` and no auth/token (`cf-fetcher.ts:134-149,181-189`); `POST /fetch {"url":…}` makes Electron's Chromium load **any** URL and returns the DOM (`cf-fetcher.ts:154-177`). Any local process — or any web page the user visits, because CORS is `*` — can drive it while the app runs. A loaded page executes JS in a fresh `contextIsolation` window (no Node), so the impact is "arbitrary web fetch through the app" (SSRF to LAN/localhost, traffic attribution, potential cookie-less session abuse) rather than RCE. Not portable and should not be replicated; if a WebView solver is implemented on Android, keep it in-process (JS bridge to Kotlin, origin allowlist, no open server).

### 6.6 Auto-updater trust

`electron-builder.yml` configures GitHub publishing (`latinokodi/tordownloader-pro`) and **no Windows code signing** (`electron-builder.yml:5-8,34-47`; no `certificateFile`, no `signingHashAlgorithms`, no `verifyUpdateCodeSignature`). Update integrity therefore rests on: `latest.yml`'s SHA-512 file hash fetched over HTTPS from GitHub Releases (`INAPP-UPDATE.md:24-31,102`). There is no Authenticode signature check and no pinned public key; compromise of the GitHub repo/release (or a token with release rights) yields arbitrary NSIS installer execution on every client. Android equivalent must not copy this: use Play App Signing/app bundle updates, or APK with signature verification + pinned release host; never download/execute installers outside the platform updater.

### 6.7 Other findings

| # | Finding | Evidence |
|---|---|---|
| 1 | `FlareSolverr` is **downloaded and executed without checksum/signature verification** from "latest GitHub release" (`asset.name.includes('windows_x64')`), extracted with PowerShell, then spawned. Supply-chain RCE if upstream release/account is compromised. | `flaresolverr.ts:82-115,162-173` |
| 2 | Plugin updater writes unsigned `.py` from `qbittorrent/search-plugins@master` into `<userData>/plugins/engines`, which `qbit-runner.py` imports with **priority over bundled engines** → arbitrary Python execution on next search. No pin/hash. | `plugin-updater.ts:163-205`; `qbit-runner.py:212-228` |
| 3 | `shell.openPath(folderPath)` via IPC accepts any renderer path; `open-folder` has no validation (`main.ts:725-728`). Low impact (opens Explorer). | `main.ts:725-728` |
| 4 | `setWindowOpenHandler` calls `shell.openExternal(url)` for **any** URL the renderer asks to open (`main.ts:96-99`). An XSS could launch arbitrary schemes (`file:`, `smb:`, custom protocol handlers). | `main.ts:96-99` |
| 5 | TMDB API key is passed to a child process via environment (`main.ts:785`), visible in the process environment to same-user processes. | `main.ts:782-786` |
| 6 | Missing CSP (`index.html:1-13`) + all secrets returned by `get-settings` = any injected script owns the accounts. | §6.1, §6.3 |
| 7 | The app talks to dozens of untrusted search sites and debrid APIs with the same process/network identity; there is no per-provider sandbox. | `helpers.py`, `latino-providers.py` |
| 8 | `torbox-has-hash` iterates the **entire** torrent list on every check (`main.ts:466-481`), called in a loop by season download — N×M API amplification. | `main.ts:466-481`; `SeasonDownloadModal.tsx:229` |
| 9 | `updateSettings`/DB dynamic-SQL identifiers (§6.3). | `db.ts:118-164` |
| 10 | No TLS pinning; axios/`https.get` use system trust. TorBox/RD rotate CDN hosts so pinning is non-trivial; document the decision on Android. | all HTTP modules |

### 6.8 Android security requirements derived from the above

1. **Secrets:** store tokens/API keys in Keystore-backed encrypted storage, not Room plaintext; expose only `hasTorbox`/`hasRealdebrid`/masked account info to UI; never round-trip raw tokens through Compose state.
2. **Refresh:** implement RD `refresh_token` grant (the desktop app's gap) and TorBox re-auth handling.
3. **Logging:** centralized redacting logger; no release logging of URLs/headers/throwables; ring buffer in memory only, never persisted with secrets.
4. **Downloads:** re-check CDN allowlist after redirects; cap redirects; verify size (and ideally hash) before "completed"; never insert download URLs into user-visible status strings.
5. **No open local servers / no dynamic code execution:** do not port `cf-fetcher` or the plugin updater.
6. **Network:** cleartext disabled; explicit decision + UI disclosure for Tor routing; per-service timeouts preserved (§8); consider separate OkHttp clients per trust domain (debrid API vs scraped sites).
7. **Updates:** Play/App-Bundle updates only; verify signer; no silent installer.
8. **Storage:** SAF/MediaStore, no `MANAGE_EXTERNAL_STORAGE` unless the product explicitly accepts the Play policy risk.
9. **Preserve `seed=0`** on TorBox `createtorrent` (`torbox.ts:93,117`) — the app must never seed or do P2P; this is a behavioural safety invariant, not a preference.
10. **Do not inherit:** the SQL-identifier interpolation, the `get-settings` secret exposure, the CF bridge, the unsigned updater, the unsigned plugin updates.

---

## 7. Features that cannot port, with candidate Android replacements

| # | Desktop feature | Why it cannot port as-is | Candidate Android replacement | Recommendation |
|---|---|---|---|---|
| 1 | `electron-updater` + NSIS (`updater.ts`, `electron-builder.yml:5-8`) | Windows installer; requires desktop GitHub asset pipeline; unsigned | Play In-App Updates (immediate/flexible), or Obtainium/GitHub APK with signature verification | **redesign** (Play) or **drop** |
| 2 | `dialog.showOpenDialog({properties:['openDirectory']})` (`main.ts:710-715`) | No native folder picker; scoped storage | `ACTION_OPEN_DOCUMENT_TREE` + persisted URI permission (`takePersistableUriPermission`) | **redesign** |
| 3 | `shell.openPath(folder)` (`main.ts:725-728`) | No "open folder in Explorer" | `ACTION_VIEW` on a `content://` URI via FileProvider, or in-app file browser/player | **redesign** |
| 4 | Arbitrary absolute paths (`destination_folder`, `movies_folder`, `series_folder`) | Android sandbox + SAF; no raw path guarantee | Single user-chosen tree root + Jellyfin subfolders; or `MediaStore` collections (Movies/TV) using `RELATIVE_PATH`; or app-specific external dir | **redesign** (one tree root recommended to limit grant prompts) |
| 5 | Python sidecars `qbit-runner(.exe)`, `tmdb-provider.exe`, `latino-providers.exe`, `subtitles.exe` | Android cannot execute PyInstaller Windows binaries; exec-from-app-data is blocked/hostile to Play | Pure Kotlin/OkHttp reimplementation (TMDB REST is trivial; subtitles OpenSubtitles-v3 + IMDB suggestion simple; latino providers = HTTP/JSON/HTML scraping; metasearch needs plugin logic) **or** Chaquopy (Python 3 in-process) with the same scripts | **redesign** — Chrome: pure Kotlin; Chaquopy only if plugin parity is required |
| 6 | 23 qBittorrent Python search plugins (`qbit-plugins/engines/*.py`) | Plugins are Python modules importing a qBittorrent shim; no Android Python by default | Port N high-value engines to Kotlin; or Chaquopy host that runs unmodified plugins; or server-side metasearch proxy | **redesign** (scope decision) |
| 7 | Bundled FlareSolverr + Chromium 142 (`electron/flaresolverr/`, 250 MB+) | Windows x64 binaries; Chrome cannot be shipped/controlled on Android; app-size and Play limits | (a) Android `WebView`-based CF solver: load URL in an invisible WebView, wait for challenge, read DOM/cookies; (b) user-supplied FlareSolverr endpoint; (c) drop CF bypass | **drop** desktop mechanism; **redesign** WebView solver (validate CF pass rate) |
| 8 | `cf-fetcher` local HTTP bridge (`cf-fetcher.ts`) | Unauthenticated localhost server; not needed if solver is in-process | Kotlin ↔ WebView JS bridge with origin allowlist, no server | **drop** |
| 9 | `better-sqlite3` (`db.ts`) | Native Node addon | Room (already in project `libs.versions.toml:9,42-44`) with migrations from §3.4 | **direct** (schema port) |
| 10 | `child_process.spawn('python'|'powershell')` (`main.ts:757-770`, `flaresolverr.ts:101-109`) | No Python/child processes on Android | Kotlin coroutines/WorkManager; no subprocesses | **drop** |
| 11 | Global `console.*` interception + Logs panel (`main.ts:54-60`) | Node console semantics; but log panel itself is portable | Timber/Logcat + in-memory ring buffer + Compose Logs screen (redacted) | **redesign** |
| 12 | Hidden `BrowserWindow` fetches (`cf-fetcher.ts:34-127`) | Electron-only | Offscreen/1×1 `WebView`, `CookieManager`, `WebViewClient.onPageFinished` + JS evaluation via `evaluateJavascript` | **redesign** |
| 13 | `app.requestSingleInstanceLock` (`main.ts:26-36`) | Android has no such lock | Single-activity `launchMode`, process-level guard, WorkManager unique work | **redesign** |
| 14 | `%APPDATA%` DB location (`main.ts:24-25`) | No APPDATA | `context.getDatabasePath()`, `filesDir`; exclude from backup or encrypt | **direct** (concept) |
| 15 | Radix dialogs/selects/toasts (`@radix-ui/*`) | Web-only | Compose `Dialog`, `ModalBottomSheet`, `DropdownMenu`, `Snackbar` | **redesign** (UI) |
| 16 | Windows NSIS/registry/ports | N/A | N/A | **drop** |
| 17 | Tor routing | **not present at all** in the original (no SOCKS/onion anywhere) | New scope: embedded `tor-android` (already pinned 0.4.9.14 in `libs.versions.toml:22,61`) or Orbot SOCKS 9050 via OkHttp `Proxy`; decide global vs per-domain | **new feature** — note debrid APIs/CDNs may reject Tor exits; must be configurable and disclosed |

---

## 8. Consolidated constants (every hardcoded timeout / retry / interval / limit in `electron/*.ts`)

| Category | Constant | Value | File:line |
|---|---|---|---|
| HTTP | TorBox client timeout | 30 000 ms | `torbox.ts:15` |
| HTTP | TorBox create/upload timeout | 60 000 ms | `torbox.ts:100,124` |
| HTTP | TorBox external .torrent fetch | 30 000 ms | `torbox.ts:137` |
| HTTP | RD client timeout | 30 000 ms | `realdebrid.ts:22` |
| HTTP | RD OAuth calls | 15 000 ms | `realdebrid.ts:35,50,81,107` |
| HTTP | RD add timeout | 60 000 ms | `realdebrid.ts:182,199` |
| HTTP | RD external .torrent fetch | 30 000 ms | `realdebrid.ts:207` |
| HTTP | Downloader request timeout | 60 000 ms | `downloader.ts:106` |
| Download | Resume: skip network if on-disk ≥ expected | n/a | `downloader.ts:86-93` |
| Download | Progress sample interval | 1 000 ms | `downloader.ts:134` |
| Download | 416 treated as complete when resuming | n/a | `downloader.ts:182-187` |
| Download | Truncation check | `bytes < expectedSize` | `downloader.ts:160` |
| Worker | Poll interval | 10 000 ms | `worker.ts:166` |
| Worker | Max concurrent local downloads | **3** | `worker.ts:20` |
| Worker | TorBox outer attempts per file | **3** | `worker.ts:373` |
| Worker | TorBox link inner attempts | **3** | `worker.ts:375` |
| Worker | Inner link backoff | `l*2000` ms | `worker.ts:380` |
| Worker | Outer retry backoff | `attempt*2000` ms | `worker.ts:383,449,466` |
| Worker | RD attempts per link | **3** | `worker.ts:556` |
| Worker | RD retry backoff | `attempt*2000` ms | `worker.ts:560,568,640,655` |
| Worker | Size acceptance threshold | `attempt >= 2` | `worker.ts:459,650` |
| Worker | DB progress throttle (TorBox) | 2 000 ms | `worker.ts:413` |
| Worker | DB progress throttle (RD) | 1 000 ms | `worker.ts:605` |
| Worker | DB progress clamp while active | 0–99 | `worker.ts:427,619` |
| Worker | Subtitle per-file timeout | 60 000 ms | `worker.ts:870` |
| Meta-search | Search timeout | 90 000 ms | `metasearch.ts:104` |
| Meta-search | Engine thread pool | `min(n, 8)` | `qbit-runner.py:244` |
| Meta-search | `as_completed` deadline | 45 s | `qbit-runner.py:258` |
| Meta-search | Per-engine result deadline | 30 s | `qbit-runner.py:261` |
| Meta-search | cloudscraper bootstrap | dev only | `metasearch.ts:60-100` |
| CF bridge | Hidden window size | 1024×768 | `cf-fetcher.ts:37-38` |
| CF bridge | Poll interval / max polls | 2 000 ms × 20 | `cf-fetcher.ts:49,90-109` |
| CF bridge | Post-load settle delay | 500 ms | `cf-fetcher.ts:113` |
| CF bridge | Fetch timeout (called with) | 60 000 ms (param default 45 000) | `cf-fetcher.ts:34,164` |
| FlareSolverr | Port | **8191** | `flaresolverr.ts:18` |
| FlareSolverr | Health interval / probe timeout | 30 000 ms / 5 000 ms | `flaresolverr.ts:19,145` |
| FlareSolverr | Startup timeout | 60 000 ms | `flaresolverr.ts:20` |
| FlareSolverr | Max restarts / restart delay | 3 / 5 000 ms | `flaresolverr.ts:21,191` |
| FlareSolverr | Ready poll interval | 1 000 ms | `flaresolverr.ts:157` |
| Updater | Failure threshold / cooldown | 3 / 6 h | `updater.ts:8-9` |
| Updater | Dismiss cooldown | 24 h | `updater.ts:10,74-80` |
| Updater | Deferred startup check | 3 000 ms | `main.ts:124` |
| Updater | Manual check UI timeout | 15 000 ms | `SettingsModal.tsx:231` |
| TMDB | Sidecar spawn timeout | 30 000 ms | `main.ts:785,831,875,920` |
| TMDB | Python HTTP timeout | 15 s | `tmdb-provider.py:42` |
| TMDB | `tmdbGet` (layout) timeout | 6 000 ms | `media-layout.ts:125` |
| TMDB | `tmdb-validate` timeout | 10 000 ms | `main.ts:974` |
| TMDB | Resolve cache TTL / prune threshold | 15 min / >500 entries | `media-layout.ts:139,163-167` |
| Latino | Sidecar spawn timeout + JS kill timer | 45 000 ms | `main.ts:987,993,996,1038` |
| Latino | Python HTTP timeouts | 15/10/8/6/5 s by call | `latino-providers.py:72,213,229,248,187-189` |
| Latino | Provider pool / deadlines | 3 workers, 30 s / 25 s | `latino-providers.py:492-505` |
| Subtitles | Python socket timeout | 15 s (IMDB suggestion 8 s) | `subtitles.py:34,60` |
| Subtitles | Max subtitle bytes | 10 MiB | `subtitles.py:33` |
| Season cache | Max entries | 100 | `season-cache.ts:13` |
| Logs | Ring buffer size | 500 | `main.ts:42`; `LogPanel.tsx:30-34` |
| Window | Size / min size | 1280×860 / 900×600 | `main.ts:82-85` |
| Renderer | Download refresh interval | 12 000 ms | `App.tsx:193` |
| Renderer | Toast auto-dismiss | 3 000 ms | `App.tsx:46` |
| Renderer | OAuth device poll (both services) | 5 000 ms | `SettingsModal.tsx:88,255` |
| Renderer | Season resolve concurrency | 4 | `SeasonDownloadModal.tsx:153` |
| Renderer | Season add stagger / transient retry / candidate pause | 1 500 / 2 500 / 1 200 ms | `SeasonDownloadModal.tsx:302,278,289` |
| Renderer | Season per-episode add attempts | 2 (transient only) | `SeasonDownloadModal.tsx:274-282` |
| Renderer | Update dialog / settings lazy * | 3 phases | `App.tsx:244-247; UpdateDialog.tsx:18-62` |

Not configured anywhere (implicit / library defaults): axios redirects (21, `follow-redirects/index.js:516`), `https.get` in `plugin-updater.ts` has **no timeout**, `tmdbGet`/`latino` follow redirects via `urllib` defaults, SQLite busy timeout, socket keep-alive.

---

## 9. Ambiguities, inconsistencies and flags (do not silently "fix" without Lead sign-off)

1. **Engine-count mismatch:** 23 engine `.py` files exist (`electron/qbit-plugins/engines/`), but the UI string says "17 engines bundled" (`src/i18n/en.ts:84`, `es.ts:86`); `README.md:18,43` says 23. Pick one number and derive it from code on Android.
2. **LEAD MODIFICATION (deliberate):** `CATALOG_BASE` changed from `https://btttr.cc/…` (BetterPosters catalog addon, still at `git show HEAD:electron/main.ts:1057`) to the Comet addon base (`electron/main.ts:1067`), and `comet-manifest`/`comet-streams` IPC + preload bridges were added (0 in HEAD). Everything else in the tree is shipped v1.0.18 behaviour.
3. **`seeds` is not persisted.** `downloads` has no `seeds`/`download_speed` column; `DownloadCard` renders `dl.seeds ?? 0` and `dl.download_speed ?? 0` (`DownloadCard.tsx:113,88`), so **cloud seed count and cloud speed are always 0** in the UI. Don't "fix" this by inventing a source; decide on Android whether to drop the field or add it.
4. **RD refresh token dead end** (§6.3). Stored, never used; UI shows "linked" until the token dies.
5. **RD dead-link comment vs code** (§5.4): comment promises skip, code fails the whole torrent.
6. **`rangeIgnored` acceptance has no size check** (`worker.ts:437-446`): a truncated non-empty file is accepted as verified. This is the main "wrong file accepted" risk in the engine.
7. **`cur >= expectedSize` early success** (`downloader.ts:88-93`) means a stale larger file is reported successful; the worker then accepts it on attempt 2 (`worker.ts:459-463`). Same risk class as 6.
8. **Retry/backoff values are Spanish-literals in persisted statuses** (`intento`) — Android strings must decide whether to keep Spanish in `local_status` for compatibility or localize the display only.
9. **`created_at` second granularity** → `ORDER BY created_at DESC` has unstable order for rows created in the same second (`db.ts:43,135`). Add a tiebreaker on Android (`created_at DESC, id DESC`).
10. **`title` from `parseReleaseName` is used for the Jellyfin filename but not TMDB-canonicalized**, while the *folder* is TMDB-canonicalized when a key is set (`worker.ts:737` vs `media-layout.ts:199`). Series can therefore land in `La Casa de Papel (2017)/Season 01/` with files named `La Casa de Papel.S01E01.mkv` (parser title) — consistent in practice but not guaranteed for translated titles.
11. **Duplicate provider plugin names:** `torrentdownload.py` and `torrentdownloads.py` are two different engines with near-identical names; do not merge them by filename.
12. **`torboxHasHash` is O(all torrents)** per call and called per episode in season adds (`main.ts:466-481`; `SeasonDownloadModal.tsx:227-235`) — rate-limit risk on Android; cache the hash set per batch.
13. **Dead code / unused exports:** `updater.isUpdateDownloaded` (`updater.ts:127-129`), `flaresolverr.getFlareSolverrUrl`/`isFlareSolverrReady` (`flaresolverr.ts:235-241`; only `getFlareSolverrStatus` is used), `subtitles.py scan` mode (`subtitles.py:200-267`, not invoked), `PreviewCard`-style CSS classes `.ascii-frame`, `.status-badge`, `.btn-play`, `.modal-overlay`, `.section-label`, `.select-field` defined in `index.css:251-313` but not referenced in any TSX (verify before porting).
14. **`onSearchError`/`onSearchProgress` now carry `tabId`** only because of the uncommitted fix (`App.tsx:209-224`); HEAD version routes everything to the active tab. A port needs explicit per-tab routing from day one (Compose `ViewModel` per query).
15. **Two different title-cleaning implementations** exist for the same job: `parseReleaseName` (`media-layout.ts:46-109`) and the subtitle title cleaner (`worker.ts:843-859`). They disagree on tags/word boundaries; folder/file names and subtitle search titles can diverge. Port decision needed.
16. **`searchBatch` fallback in season downloads** uses a plain keyword query `${seriesTitle} SxxEyy` (`SeasonDownloadModal.tsx:84,132-137`) — the quality of fallback results depends on the metasearch engines, not the providers.
17. **No CSP** (`index.html`), no code-signing config (`electron-builder.yml`), no hash verification of FlareSolverr/plugins (§6.6-§6.7) — do not carry over.
18. **`get-downloaded-episodes` re-run detection** relies on: exact computed folder first, then a depth ≤6 scan of `series_folder` gated on accent-folded title tokens (≥4 chars, stop-word list in `main.ts:216`), then DB rows for the same season/type. On Android, SAF tree traversal is expensive — design an index instead of scanning, but keep the same "failed transfers are re-addable / queued are skipped" semantics (`main.ts:263-275`).
19. **Stop-word list** is bilingual (EN+ES) and hard-coded (`main.ts:216`); it is part of the re-run matching behaviour.
20. **Header height / sidebar width:** sidebar is exactly `w-64` = 256 px (`Sidebar.tsx:48`); the header has **no explicit height** (padding `p-6` + text sizes only, `Header.tsx:13`), so any 56/64 dp Android app-bar claim must be a redesign decision, not a "parity" claim.
21. **Window-level assumption:** the whole app is `h-screen overflow-hidden` with an always-visible sidebar (`App.tsx:250-261`; no mobile breakpoint/collapse). Android requires a navigation redesign (drawer/bottom bar) — flag in UI docs.
22. **`app.setName` comment** documents a real Electron 42 pitfall (`main.ts:17-23`); irrelevant on Android but explains why the DB path is explicit.

---

## 10. Porting checklist (derived from this document)

- [ ] Room schema mirroring §3.2/§3.3 with the §3.4 migration history collapsed into a single baseline + future migrations.
- [ ] Settings datastore with the 12 fields + defaults (§3.6); tokens encrypted; secrets not returned to UI.
- [ ] `parseReleaseName`/`computeDestination`/`safeSegment` ported with §5.7 regexes + the 12 test vectors as unit tests.
- [ ] Download engine implementing §5.2-§5.5 semantics, including resume, truncation, 200/416, `rangeIgnored`, `attempt>=2` acceptance, 3×3 retries, 3 concurrent, 10 s poll, progress formats.
- [ ] Jellyfin rename + subtitle pipeline in the same order (§5.8-§5.9), scoped to own files.
- [ ] Debrid clients with §4.1/§4.2 endpoints, timeouts, normalizers, device flows; RD refresh implemented (gap fix).
- [ ] TMDB + latino + metasearch reimplemented (§4.3, §4.8, §4.9) with streaming progress events replacing IPC events.
- [ ] Settings/Transfers/Logs/Discover screens per `docs/ui-inventory.md` tokens.
- [ ] Tor routing as a new, explicit layer (§7.17) — never in the original.
- [ ] Security requirements §6.8 enforced before first release.

---

*End of source analysis. Companion documents: `docs/ui-inventory.md` (screens, components, tokens, i18n), `docs/parity-draft.md` (feature matrix with Android portability and tests).*

