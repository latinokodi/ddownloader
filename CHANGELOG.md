# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning: [SemVer](https://semver.org/) (see `VERSIONING.md`). Generated/curated following the
`changelog-automation` skill: entries are grouped by type, written for users, and reused verbatim as
GitHub Release notes by `RELEASING.md`.

## [Unreleased]

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
