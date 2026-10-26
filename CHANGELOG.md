# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning: [SemVer](https://semver.org/) (see `VERSIONING.md`). Generated/curated following the
`changelog-automation` skill: entries are grouped by type, written for users, and reused verbatim as
GitHub Release notes by `RELEASING.md`.

## [Unreleased]

## [1.7.0] - 2026-10-26

### Changed — mobile-first, adaptive layout
- The studio rail is now **wide-window only** (>= 700 dp). Phones get a single pane with a flat
  bottom bar (amber indicator, queue badge, logs) — the rail would have consumed 52% of a 411 dp
  phone's width.
- `ScreenContent` extracted so both layouts share one screen switch; `ContentPane` still caps and
  left-aligns content on wide windows.
- Verified on a 1080x2400 phone (411 dp) and on the 1920x1080 tablet window.


## [1.6.1] - 2026-10-26

### Changed — "Studio" layout, a structural rebuild
- **Navigation moved to a persistent left rail** (212 dp): brand, live status, the five
  destinations with a 2 dp amber keyline on the current one, the queue badge, and the app version.
  The bottom bar and the top bar are gone — the rail carries identity, navigation and state.
- **Near-black warm palette** with a single amber accent (#F5A524) and lime for success. No
  gradients, no glows, no borders around every surface: flat panels, hairlines and spacing.
- **Oversized type**: 44/36/28/22/20 sp headings with tight tracking, mono eyebrows at 10.5 sp with
  1.1 sp tracking.
- **Near-square geometry**: 3–10 dp radii (was 8–16), square chips, filled inputs without a resting
  border, text-only secondary buttons.
- Content column capped and left-aligned in the remaining pane (`ContentPane`), ready for the
  two-pane split.


## [1.5.2] - 2026-10-26

### Added
- The verify variant skips onboarding (`BuildConfig.VERIFY_HARNESS`), so UI journeys can reach the
  real screens on a clean device without provider credentials. This is what allowed the Graphite
  redesign to be verified on an AVD while the tablet emulator was closed.

### Fixed
- Version bump / rebuild of the 1.5.x line so the published APK matches the verified build.


## [1.5.0] - 2026-10-26

### Changed — design system rebuilt ("Graphite")
- **Palette**: neutral graphite surfaces (#0D1117 → #1F2732) with a single azure accent (#4C82F7) for
  actions and live state, indigo for brand marks, and neutral signal colours. The violet→cyan
  gradient that appeared on every surface is gone; the accent now means "you can act on this".
- **Typography**: one face (Inter) for all UI text — the geometric display font and the shouted
  gradient wordmark were the least professional elements. Mono (JetBrains Mono) is kept only for
  labels and telemetry, where digits must not dance. Tighter scale: 34/28/22/19/18/15/13.5/12.
- **Radii**: 22–24 dp everywhere → 8–16 dp. Cards are flat graphite with a hairline border and a
  2 dp accent keyline instead of a gradient border.
- **Buttons**: primary is 42 dp with a 10 dp radius and a solid ramp (white text stays AA at 5.1:1);
  secondary/ghost is transparent with a hairline border, no fill; pills are 6 dp chips without
  borders.
- **Home**: the "DOWNLOAD ANYTHING" hero is replaced by a page header (title + one orientation
  line), three flat shortcut cards with live counts, and a compact status strip.
- **Backdrop**: the three-colour aurora became one very slow, low-contrast azure glow.
- All features, screens, flows, translations and states are unchanged.


## [1.4.4] - 2026-10-26

### Changed
- **Card headers no longer misalign.** "Re-check" in Debrid accounts was a 44 dp bordered button on
  the same line as a 26 dp icon label, so it floated above the copy and looked bolted on. It is now
  a quiet `CardAction` (glyph + small label, no chrome) sized to the header, aligned to its baseline.


## [1.4.1] - 2026-10-26

### Fixed
- **Remaining English in Settings**: the hardened/backup metrics ("Excluded (cloud + transfer)",
  "Credentials auto-redacted", "HTTPS only • cleartext blocked", "AES-256-GCM • Android Keystore"),
  the "HARDENED" pill, export/import toasts and the notification permission toasts. Also Discover's
  empty state, the link dialog's "Done", the update dialog's "Contacting GitHub Releases…"/"Retry",
  and the Keystore self-test / output-folder / language toasts.
- **TMDB key validation now trims whitespace** before calling the API. A pasted key usually carries a
  trailing newline and TMDB answers 401 for it, which looked like "key rejected" when the key was
  fine. The failure message now names the reason (`HTTP 401` vs `no network`) instead of a blank
  rejection.


## [1.4.0] - 2026-10-26

### Changed — the revamp and the translation are complete
- **Settings sections** now carry a tinted icon tile and label (`SettingsSection`) instead of seven
  flat uppercase captions, so the screen scans as grouped settings rather than paragraphs.
- **Onboarding** gained a four-segment gradient progress bar that fills as you advance and tracks
  the drag while paging.
- **The log stream is localized.** Diagnostics are written where the event happens (HTTP redirects,
  the download manager, the service locator), so they cannot read `LocalStrings`; a new `LogL10n`
  holds the active language and every log template is now a translated pair. This closes the last
  English text visible in the Spanish UI.


## [1.3.3] - 2026-10-26

### Fixed
- The top bar rendered the literal `secureSession` instead of "SECURE SESSION": the English value
  was accidentally set to the field name itself when the new keys were added. 1.3.2 shipped with
  this typo; the English label is restored.


## [1.3.2] - 2026-10-26

### Changed — the whole UI now follows the language setting
- Every screen reads its copy from the `Strings` table: home hero, search card, results, stream
  sheet, Transfers, Logs, Discover (including shelf and genre names via `ShelfNames`), the update
  dialog/banner and the top bar. Previously only Settings, onboarding and navigation were translated.
- Every ViewModel toast (validation errors, queue results, account state, update failures) is
  localized too, using the persisted language rather than the activity's locale.
- 97 new keys added to both locales (EN + ES), including formatted ones (`transferSummary`,
  `logsSummary`, `updateAvailable`, `resumeInterrupted`).

### Fixed
- The stream sheet said "Asking Comet through Tor…" — a leftover from the original project that
  contradicted the product (no Tor network involved). Now "Asking Comet for streams…".
- English leaked into the Spanish UI in the top bar (SECURE SESSION / TRANSFERRING), Settings
  update row (Status) and the update dialog.


## [1.3.1] - 2026-10-26

### Changed
- Version published specifically to exercise the in-app updater end to end (download, SHA-256 and
  signer verification, installer hand-off). No functional change.


## [1.2.2] - 2026-10-26

### Fixed
- **Remove did nothing on finished/failed transfers.** The button called `cancel()`, which only
  marks a row CANCELLED and keeps it, so for a row that was already terminal nothing changed on
  screen. Added `DownloadManager.remove()` (stops any live job, deletes the partial file, deletes
  the row) and pointed every terminal-state Remove at it. Verified on device: the row disappears
  and the queue returns to "No transfers yet".


## [1.2.1] - 2026-10-26

### Fixed
- **Settings (and every other migrated screen) rendered broken in 1.2.0**: `AuroraCard` replaced
  `GlassCard` but did not provide the `ColumnScope` + 16 dp padding that `GlassCard` had, so its
  children fell out of layout and overlapped. `AuroraCard` now takes a `contentPadding` and hosts a
  `Column`, restoring the old contract; Transfers passes `contentPadding = 0.dp` because it lays
  out its own padded column. Screenshot-verified on device.


## [1.2.0] - 2026-10-26

### Changed — visual system revamp
- **Motion system** (`ui/theme/Motion.kt`): one set of durations (140/260/420 ms), two easings, a
  scale-on-press modifier, a shimmer brush and an animated aurora backdrop. Every animation in the
  app now comes from here instead of ad-hoc values, and all of it honours the system
  "remove animations" setting.
- **Aurora backdrop** behind every screen: three slow, offset radial gradients in the brand hues.
  Replaces the flat near-black fill.
- **Cards unified**: one `AuroraCard` (gradient hairline border + near-black fill + one accent) is
  now the only surface in the app — Discover, Search, Settings, Onboarding and Transfers.
- **Transfers screen rebuilt** as a control room: four stat tiles (active/queued/done/failed),
  animated progress ring per transfer, provider + state pills, a 3 dp progress rail, done/total,
  live speed and ETA, and context-sensitive actions (Stop / Retry + Remove / Remove). Cards
  animate when the list reorders.
- **Logs rebuilt** as a terminal: animated filter chips, monospace rows with a level stripe
  (colour *and* letter, so it survives colour-blindness), Copy-all and Clear.
- **Screen transitions** slide+fade on the emphasized easing; the bottom-nav selection pill
  animates its tint instead of snapping, and the Downloads badge springs in and out.
- Poster cards (Discover and Search results) now scale on press with no ripple.
- Discover skeletons use a moving shimmer instead of flat placeholders.


## [1.1.3] - 2026-10-26

### Changed
- **Search results are a poster grid**, not a list: adaptive columns (132 dp minimum, so ~8 on a
  landscape tablet and ~3-4 on a phone), 2:3 artwork, rating badge and a "year · type" caption.
  The hero, search card and paste-link card still span the full width.

### Added
- **Live badge on the Downloads tab**: the count is every transfer that has not reached a terminal
  state, so it goes up as downloads are queued and down as they complete or fail. It carries a
  content description ("N unfinished downloads") for screen readers, and caps at 99+.


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
