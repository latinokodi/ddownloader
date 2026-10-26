# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning: [SemVer](https://semver.org/) (see `VERSIONING.md`). Generated/curated following the
`changelog-automation` skill: entries are grouped by type, written for users, and reused verbatim as
GitHub Release notes by `RELEASING.md`.

## [Unreleased]

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
