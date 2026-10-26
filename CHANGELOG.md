# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning: [SemVer](https://semver.org/) (see `VERSIONING.md`). Generated/curated following the
`changelog-automation` skill: entries are grouped by type, written for users, and reused verbatim as
GitHub Release notes by `RELEASING.md`.

## [Unreleased]

## [1.1.2] - 2026-10-26

### Fixed
- **TorBox downloads actually resolve and transfer.** Two real defects found by running a
  magnet through the whole pipeline on a device:
  1. `/torrents/requestdl` requires the API token as a **query parameter** as well as the
     Authorization header; without it TorBox answers `422 {"loc":["query","token"]}`.
  2. The CDN allowlist did not cover TorBox's regional CDN domains (`store-018.weur.tb-cdn.st`),
     so a correctly resolved link was refused with "non-allowlisted host". The suffix list now
     covers `tb-cdn.st`, `tb-cdn.com`, `torbox.dev` and the Real-Debrid CDN domains.
- `requestdl` failures now log the API's own error body, so a 422 names the missing field.

### Added
- `Pipeline` log lines at each stage (magnet → createtorrent → file list → enqueue → transfer
  completion), which is what made the two defects above findable.


## [1.1.0] - 2026-10-26

### Added
- **Discover rebuilt as a shelf catalogue**: 12 categories per type — Trending this week, Popular,
  Top rated, In cinemas / Airing today, Coming soon, plus genre shelves (Action, Comedy, Horror,
  Sci-Fi, Animation, Thriller, Drama, Crime, Sci-Fi & fantasy, Mystery, Reality, Documentary).
  Shelves load in parallel, show skeletons while loading, and one failed shelf never blanks the
  page. Posters carry a rating badge; Movies / Series is a segmented control.
- **Magnet links can be pasted** into the Search tab ("https://… link or magnet:…").
- **Multi-file torrents queue every video file** (season packs now produce several downloads
  instead of silently keeping only the biggest file).
- TorBox gives the cloud up to ~75 s to list a torrent's files instead of failing on the first
  lookup with "file list not ready yet".

### Changed
- Downloads/Copy: search placeholder mentions magnets; addUrl hands magnets to the debrid flow.


## [1.0.10] - 2026-10-26

### Changed
- **HTTP layer owns its dispatcher.** Every `Web` helper (`get`, `postJson`, `postForm`,
  `formDelete`, `postMultipartFields`) is now `suspend` and hops to `Dispatchers.IO` itself, and
  the service clients (`TorBoxApi`, `RealDebridApi`, `CometApi`) are `suspend` accordingly. The
  recurring `NetworkOnMainThreadException`-swallowed-by-`runCatching` bug cannot be reintroduced:
  the compiler now rejects calling these outside a coroutine.
  This is what caused "Could not add magnet: null" (and earlier the empty Discover/Search and
  the dead TMDB validate button).
- `Web` also throws an explicit `IOException` if a request is ever executed on the main thread,
  so the failure mode is a readable message instead of `null`.

### Fixed
- Empty Downloads state no longer mentions the Tor network.


## [1.0.9] - 2026-10-26

### Fixed
- **Discover and Search return results.** TMDB calls were made with blocking OkHttp on the
  caller's dispatcher (Main, from `viewModelScope`), throwing `NetworkOnMainThreadException`,
  which the surrounding `runCatching` converted into `null` → an empty list → "TMDB returned no
  titles". `TmdbApi.get()` (search, trending, resolveTitle, imdbId) now runs on
  `Dispatchers.IO`, logs the failing path with the HTTP status instead of a null message, and
  logs explicitly when no key is stored.
- Comet stream requests are dispatched off the main thread as well.
- Discover surfaces the real failure reason instead of a generic "check the API key".


## [1.0.8] - 2026-10-26

### Fixed
- **TorBox linking works.** The device-token endpoint answers HTTP 200 while the user has not
  authorized yet and reports the state in the body (`success:false`, `error:DEVICE_CODE_NOT_USED`
  / `ITEM_NOT_FOUND` / `DEVICE_CODE_USED`). The poller treated any 200 as authorized and then
  failed with "authorized but returned no token". It now branches on the body, and extracts the
  credential from any of the shapes the API may use (top level, `data`, nested, `data` as a
  string, arrays, single opaque string).
- **"Validate & save" for the TMDB key actually validates.** The check ran blocking network I/O
  inside a main-thread coroutine, threw `NetworkOnMainThreadException`, and the surrounding
  `runCatching` reported every key as invalid. It now runs on `Dispatchers.IO`, and the result is
  shown inline (checking / validated / rejected with reason) instead of only as a toast.
- **The setup assistant had no snackbar host**, so every message raised during setup was
  silently dropped and controls looked dead. It now hosts a snackbar like the main UI.
- TMDB step copy said "Optional"; it is required, and now says so.


## [1.0.6] - 2026-10-26

### Changed
- TorBox account linking now shows the **short link `https://tor.box/link`** instead of the
  long `https://torbox.app/oauth/device?app=DDownloader` URL returned by the API. The shortlink
  redirects to the same device page; the long URL is still logged (tag `DeviceAuth`) for
  diagnostics but is no longer presented to the user.


## [1.0.5] - 2026-10-26

### Changed
- **Setup is mandatory.** The first-run assistant no longer has a "Skip" path: it requires
  at least one linked debrid account *and* a validated TMDB API key before the app can be used.
  - "Continue" is disabled on the debrid step until an account is linked, and on the TMDB step
    until the key has been validated against `api.themoviedb.org/3/configuration`.
  - The rule is also enforced in `AppViewModel.completeOnboarding()`, so a stale UI state cannot
    bypass it, and the assistant no longer allows swiping between steps.
  - The header shows two requirement dots that fill in as each requirement is met, plus the
    EN/ES language switch, so the mandatory step is also the place to change language.

### Fixed
- Onboarding copy said "optional" for the TMDB key; it is required now.


## [1.0.3] - 2026-10-26

### Changed
- **Debrid accounts are now added by URL + code** (device authorization), not by pasting an
  API token. The app requests a code, shows the provider's verification URL and the code, and
  polls until the user authorizes; the credential is stored encrypted and never displayed.
  - TorBox: `GET /v1/api/user/auth/device/start` → `POST /v1/api/user/auth/device/token`
  - Real-Debrid: `device/code` → `device/credentials` → `token` (device grant, per-user
    client credentials kept for refresh)
- **English / Spanish language toggle** in Settings (persisted, applied instantly through a
  composition-local `Strings` layer covering navigation, the assistant, Settings and the
  linking dialog).

### Fixed
- TorBox device-authorization path was built with a doubled `/api` segment, which the API
  answered with HTTP 404 (caught by running the flow on a device, not by review).


## [1.0.1] - 2026-10-26

### Added
- **First-run assistant** (4 steps): debrid authorization, optional TMDB key, download folder,
  with a “Skip” path and per-step validation feedback.
- **Debrid authorization by API token** with live proof: the token is checked against
  `/user/me` (TorBox) and `/user` (Real-Debrid) and shown as Authorized / Rejected / Unverified.
- **Self-update from GitHub Releases**: checks the release feed (throttled to once per 6 h, and
  never re-offers a dismissed version), downloads the APK, verifies its SHA-256 when published and
  its **signing certificate against the installed app**, then hands it to the system installer.
  Settings, tokens and the transfer queue are preserved by the in-place update.
- Settings → Updates card and a launch-time update prompt.
- CI (`GitHub Actions`): unit tests, lint, detekt, APK build and an OSV dependency scan.
- `VERSIONING.md`, `RELEASING.md`, `THREAT-MODEL.md`, `PERFORMANCE.md`, `DEPENDENCIES.md`,
  `PRE_RELEASE_REVIEW.md`, journey tests under `journeys/`.

### Changed
- **Renamed the app to DDownloader** (applicationId `com.ddownloader.android`); all in-app copy
  that explained the old name was removed.
- Downloads hero button now navigates to the Downloads tab instead of doing nothing.

### Fixed
- Network loss during a transfer never recovered on its own: a connectivity callback plus a
  network-constrained WorkManager retry now re-arms the transfer, and the download client has a
  finite 30 s read timeout so a stalled connection fails instead of hanging.
- A server that ignores `Range` could publish a truncated file as complete; the partial is now
  discarded and re-fetched, then verified.
- Bottom-nav icons announced their label twice to screen readers.

## [1.0.0] - 2026-10-26

### Added
- Initial public release: TorBox + Real-Debrid client in Kotlin/Compose, resumable download engine
  (HTTP Range, `If-Range`, 416, truncation detection, 3 attempts), foreground service
  (`dataSync`) with Android 15 `onTimeout` handling, WorkManager resume, scoped-storage output,
  Keystore-encrypted tokens, redacting logger, HTTPS-only transport with per-hop redirect
  validation, Jellyfin folder layout ported 1:1 from the Electron app.
