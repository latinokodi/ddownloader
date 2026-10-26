# UI Inventory — TorDownloader PRO (Electron renderer) → Compose mapping

| Field | Value |
|---|---|
| Subject | `F:\PyApps\tordownloaderElectron\src` + `tailwind.config.js` + `src/index.css` + `index.html` |
| Snapshot | Same working tree as `docs/source-analysis.md` §0.3 (base commit `1f70fd4` + uncommitted `tabId` routing fix). `src/App.tsx` SHA-256 prefix `254150416BAD`. |
| Scope | Screens, states, interactions, component props, design tokens, layout metrics, i18n, and a concrete Compose equivalent for each web idiom. |
| Window assumption | Desktop window 1280×860, minimum 900×600 (`electron/main.ts:80-86`). There is **no mobile layout** — the shell is a fixed sidebar + main column. Android requires a navigation redesign; only the token/visual language is reusable. |
| Rendering stack | React 18 + Tailwind 3.4 + Zustand + `lucide-react` icons. `@radix-ui/*`, `gsap`, `clsx`, `tailwind-merge`, `class-variance-authority` are in `package.json:18-33` but **zero imports in `src/`** (verified by grep) — no component library theming to port; every widget is bespoke Tailwind. |

---

## 1. Navigation model

There is **no router**. `App.tsx` holds a single string state `activeNav` (`src/App.tsx:35`) set by the sidebar; the four values are `'search' | 'downloads' | 'logs' | 'discover'` and each renders one branch inside a shared `<main>` (`App.tsx:279-378`). Overlays are separate state, not routes:

- `showSettings` → lazy `SettingsModal` (`App.tsx:34,382-386`)
- `seasonModalOpen` (inside DiscoverView) → `SeasonDownloadModal` (`DiscoverView.tsx:29,402-413`)
- `updatePhase` (derived) → `UpdateDialog` (`App.tsx:244-247,388-396`)
- `toast` → `Toast`, auto-dismissed after 3 000 ms (`App.tsx:41-48`)
- Search history: Zustand `searchTabs` store keyed `"1"`, `"2"`… with `activeTabId` (`src/store/searchTabs.ts:40-51`); the tab bar renders above the results and is not part of navigation.

Shell layout (`App.tsx:249-401`):

```
<div class="flex h-screen w-full bg-bg-deep text-text-main font-display overflow-hidden relative">
  <Sidebar/>                                   // 256px fixed, full height
  <main class="flex-1 flex flex-col relative z-10 h-full overflow-hidden">
    <Header/>                                  // p-6, bg-bg-card, border-b
    <MagnetBar/>                               // only when activeNav==='search'
    <div class="flex-1 overflow-auto p-6">     // screen content
  </main>
  <SettingsModal/> <UpdateDialog/> <Toast/>
</div>
```

### Screens

| Screen | Purpose | Entry | States observed | Key interactions |
|---|---|---|---|---|
| **Search** | metasearch across 23 qBittorrent engines, add result to debrid | sidebar "Search" | empty prompt (`SearchTab.tsx:95-102`); loading with per-engine label (`:118-143`); error with no results (`:104-115`); "no results" (`:145-152`); partial results + yellow warning banner (`:157-161`); results list (`:162-194`) | type query → `Initialize Search`; switch/close query tabs; choose type None/Movie/Series per row; `Acquire` adds via magnet/.torrent/info-hash; toast on success/failure |
| **Transfers** | one card per cloud transfer + local download | sidebar "Transfers" (badge = total rows) | empty "No active downloads" (`App.tsx:335-339`); list of `DownloadCard`; "Open Dest" disabled when no `destination_folder` (`App.tsx:311-318`) | Open Dest (`shell.openPath`), Clear Completed, per-card Cancel ⏹ / Delete X |
| **Logs** | in-memory app log viewer | sidebar "Logs" | empty text (`LogPanel.tsx:63-67`); log lines with level colors; autoscroll to bottom (`:40-44`) | Clear (clears renderer copy only, not the main-process buffer) |
| **Discover** | TMDB catalog + latino provider search, season packs | sidebar "Discover" | no TMDB key (`DiscoverView.tsx:309-319`); loading lists (`:322-331`); lists error + Retry (`:334-344`); TMDB grid / search results / Comet-catalog grid (`:418-534`); detail view (`:346-416`); season modal | TMDB search, catalog dropdown (`:467-492`), Movies/TV tabs + Trending/Popular/Top-rated (`:496-524`), thumbnail click → detail, season → episode → provider results; "Descargar Temporada N" opens `SeasonDownloadModal` |
| **Settings** (modal) | credentials, OAuth linking, folders, TMDB key, updates | sidebar Settings or after save | returns `null` while loading (`SettingsModal.tsx:273`); loaded form; OAuth waiting state (`:322-333,354-365`); inline status lines for TMDB/plugins/update | link/relink TorBox & RD (poll 5 s), browse 3 folders, validate TMDB key, toggle auto-remove, test engines, update plugins, check for updates, Save/Cancel |
| **Update dialog** (overlay) | electron-updater prompt | auto event / Settings check | `available` / `downloading` / `downloaded`, nothing when `null` (`UpdateDialog.tsx:16-62`) | Not now, Download, Later, Restart Now |
| **Toast** | transient success/error | any action | success (left border success) / error (left border danger) | auto-dismiss 3 s; not clickable |

Overlay dismissal: `SettingsModal` closes on backdrop click + ✕ + Cancel (`SettingsModal.tsx:276-281,502`); `UpdateDialog` **only** via buttons; `SeasonDownloadModal` only via ✕ / Cerrar, and both are disabled while adding (`SeasonDownloadModal.tsx:342,423`). No Escape-key handling and no focus trap anywhere.

---

## 2. Component-by-component

| Component | Props | What it renders | States | Key classes (verbatim) | Android equivalent |
|---|---|---|---|---|---|
| `App` | — | sidebar + header + screen switch + overlays | active nav, downloads array, settings modal, update phase, toast | root `flex h-screen w-full bg-bg-deep text-text-main font-display overflow-hidden relative` | `Scaffold` + `NavHost`/state, `SnackbarHost` |
| `Sidebar` (`memo`) | `activeNav, onNavChange, onSettingsOpen, downloadCount, flaresolverrStatus, onFlareSolverrRestart, appVersion` | brand `TB` block (32 px, accent bg, black mono), status dot, app title + `v{version}`, 4 nav items with lucide icons, optional count badge, Cloudflare status row + Restart button, `LangToggle`, Settings button | active item, badge>0, 4 flare statuses (`ready/starting/failed/off`) with dot+text colors | `w-64 border-r border-border bg-bg-panel flex flex-col`; brand `p-6 border-b`; `w-8 h-8 bg-accent ... font-black font-mono`; nav `flex-1 p-4 space-y-2`; item active `bg-accent/10 text-accent border-l-2 border-accent`, inactive `text-text-muted hover:text-text-main`; badge `bg-accent text-bg-deep px-2 py-0.5 text-xs font-bold`; bottom `p-4 border-t` | `ModalNavigationDrawer` (phone) or `NavigationRail` (tablet); brand as header; destinations 3–4 + Settings; status row becomes Tor/debrid service row; language as settings item |
| `Header` (`memo`) | `activeCloud, activeLocal` | title `Download Control`, subtitle, two right-aligned counters | counts | `border-b border-border p-6 bg-bg-card flex justify-between items-end`; `h1 text-2xl font-black uppercase tracking-tight text-text-heading`; subtitle `text-sm font-mono text-text-muted mt-1`; counters `font-mono text-xs uppercase tracking-widest`, numbers `text-xl font-bold` accent/success | `TopAppBar` (title + subtitle) or custom `Row`; counters as two `AssistChip`s / badges |
| `MagnetBar` | `value, onChange, onSubmit, adding, service, onServiceChange, hasTorbox, hasRealdebrid, visible` | magnet icon, input, `ADD LINK` button, service select | hidden when not Search; disabled while adding; disabled + tooltip when no service | `magnet-bar px-6 py-3 bg-bg-deep border-b border-border flex gap-3 items-center`; input `input-field flex-1 text-sm font-mono`; button `btn btn-accent whitespace-nowrap font-mono text-xs` | `BottomAppBar`/search-screen `Row` with `OutlinedTextField` + `Button`; paste via clipboard action; service `DropdownMenu` |
| `ServiceSelector` | `service, onChange, hasTorbox, hasRealdebrid, disabled?` | `TorBox` / `Real-Debrid` select | renders `null` unless **both** services configured (`ServiceSelector.tsx:10`) | `btn border border-border text-xs px-2 bg-bg-deep font-mono text-text-main` | `SegmentedButton` / `DropdownMenu`, hidden unless both configured |
| `SearchTabsBar` | — (store) | horizontal chips: query, current engine while loading, × close | `null` when no tabs | `flex gap-2 mb-4 overflow-x-auto pb-2 scrollbar-none`; chip active `bg-accent text-bg-deep border-accent`, idle `bg-bg-panel text-text-muted border-border hover:border-accent`; query `truncate max-w-[150px]` | `ScrollableTabRow` or `LazyRow` of `FilterChip`s with close icon; one screen route per query |
| `SearchTab` | `onDownloadAdded, showToast, service, serviceAvailable` | empty/loading/error/no-results states; result rows (title, seeders/peers/size/indexer, type select, Acquire) | per-tab loading engine, error, partial results | row `glass-panel p-4 flex justify-between items-center transition-colors hover:border-accent`; title `font-bold text-text-heading truncate`; meta `text-xs font-mono text-text-muted mt-2 uppercase tracking-wide`; spinner `w-4 h-4 border-2 border-bg-panel border-t-accent rounded-full animate-spin` | `LazyColumn` + `ListItem`; type `DropdownMenu`; `Button` per row; loading row with `CircularProgressIndicator(2.dp)`; error/snackbar |
| `DownloadCard` (`memo`) | `download, onDelete, onCancel` | name; SEEDS/speed/ETA/service tag; Cancel ⏹ or Delete X; status+percent; 4 px progress bar; two-cell cloud/local grid | complete / failed / cloud-done-downloading / in-cloud; active vs terminal | `glass-panel p-4 relative flex flex-col gap-3 group queue-item`; buttons `absolute top-3 right-3 !p-1 !h-8 !w-8`; progress track `w-full h-1 bg-bg-deep border border-border overflow-hidden relative`, fill `h-full bg-accent transition-all duration-300`; grid `grid grid-cols-2 gap-2 text-xs font-mono uppercase bg-bg-deep p-2 border border-border`; RD tag inline color `#00a6fb` | `Card`/`Surface` + `Column`; `LinearProgressIndicator(progress = {p})` 4.dp, accent; two-column status `Row`; FilledTonalIconButton for cancel/delete (48.dp min, see a11y note) |
| `LogPanel` | — | header + Clear, monospace log lines, autoscroll | empty | `flex flex-col h-full`; lines `font-mono text-[11px] leading-relaxed`, timestamp `opacity-40`, levels info `text-text-muted` / warn `text-yellow-400` / error `text-danger` | full-screen `LazyColumn` (11.sp mono), `TopAppBar` with Clear; `rememberLazyListState` autoscroll |
| `DiscoverView` | `onDownloadAdded, showToast, service, hasTorbox, hasRealdebrid` | TMDB lists grid / search results / catalog grid; detail header (poster, title, year, runtime, genres, overview); `SeriesPicker`; season-download button; `DiscoverResults`; `SeasonDownloadModal` | no key / list loading / list error / empty / detail / catalog | section `space-y-6`; detail `glass-panel p-4 flex gap-4`, poster `w-24 h-36 object-cover`; tabs `btn text-xs px-4 py-2 font-mono uppercase tracking-wider`; active `btn-accent` | nested `NavHost` or state machine in one route; `LazyVerticalGrid`; detail `Column` with poster `AsyncImage` 96×144 dp |
| `DiscoverResults` | `onDownloadAdded, showToast, service, hasTorbox, hasRealdebrid, mediaType` | provider results list + loading banner + per-row service select + Descargar | loading no-results / loading with results / empty / results | row identical to SearchTab; loading `bg-bg-deep border border-accent/30 font-mono uppercase tracking-widest text-sm` | same `LazyColumn` as search results; per-row `DropdownMenu` |
| `DiscoverThumbnails` | `items, onClick` | poster grid with rating badge, title | poster missing → `?` tile | `grid grid-cols-4 sm:grid-cols-5 md:grid-cols-6 lg:grid-cols-7 xl:grid-cols-8 gap-1.5`; tile `glass-panel overflow-hidden cursor-pointer hover:border-accent hover:scale-[1.02]`; poster `aspect-[2/3]`; rating `absolute top-1 right-1 bg-accent text-bg-deep text-[10px] font-bold px-1.5 py-0.5` | `LazyVerticalGrid(GridCells.Adaptive(104.dp))`, `aspectRatio(2f/3f)`, rating `Badge` top-end |
| `SeriesPicker` | `onSelect` | season chips, episode list (E number, name, air date, overview) | loading episodes / empty / list | seasons `btn text-xs px-3 py-1.5 font-mono`; episodes `grid gap-2 max-h-64 overflow-y-auto`; selected `border-accent bg-accent/10`; `line-clamp-2` | `LazyRow` season chips + `LazyColumn` episode rows in a bounded height |
| `SeasonDownloadModal` | `seriesTitle, imdbId, season, episodes, service, onClose, onDownloadAdded, showToast` | per-episode rows with status badge, candidate dropdown, Include/Skip, error text; footer counts + Cerrar/Descargar | resolving `n/N` / ready / adding `n/N` / ok / dup / present / error / skipped (`opacity-40`) | overlay `fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4`; panel `glass-panel w-full max-w-2xl max-h-[85vh] flex flex-col`; row `glass-panel p-3 flex items-start gap-3`; badges ✓ ⧉ ✗ spinner | full-screen route/dialog (`ModalBottomSheet` too small) with `LazyColumn` + sticky bottom bar; per-row status icon + `DropdownMenu` |
| `SettingsModal` | `onClose, showToast` | sectioned form: Search engines, TorBox, Real-Debrid, Local storage (TMDB key, 3 folders, auto-remove), App updates; footer Cancel/Save | loading (null), linked/unlinked per service, OAuth waiting code, inline statuses | overlay `fixed inset-0 z-50 bg-black/80 backdrop-blur-sm ... p-6 animate-fade-in`; panel `glass-panel w-full max-w-2xl bg-bg-panel border border-border shadow-2xl flex flex-col`; header `p-6 border-b ... bg-bg-deep`; form `p-6 flex flex-col gap-8 overflow-y-auto max-h-[70vh]`; section titles `text-accent font-mono text-sm uppercase tracking-widest border-b border-border pb-2`; OAuth code `text-4xl font-black tracking-[0.5em] text-accent font-mono` | full-screen settings route (form is 500 px tall, bad as a phone dialog); sections as `ListItem`s; OAuth code as large selectable text + Custom Tab button |
| `UpdateDialog` | `phase, updateVersion, updatePercent, onDismiss, onDownload, onLater, onRestart` | centered modal, 3 phases | available / downloading / downloaded / null | overlay `fixed inset-0 z-50 bg-black/80 backdrop-blur-sm ... p-6 animate-fade-in`; panel `glass-panel w-full max-w-md bg-bg-panel border border-border shadow-2xl p-8 text-center space-y-6`; progress track `w-full bg-bg-deep border border-border h-3` | **drop**; if self-update kept use `AlertDialog` + `LinearProgressIndicator`; prefer Play in-app update UI |
| `Toast` | `message, type, visible` | bottom-right notification | success/error | `fixed bottom-6 right-6 z-50 glass-panel p-4 px-6 border-l-4 font-mono text-sm uppercase animate-slide-up`; error `border-l-danger text-danger`, success `border-l-success text-success` | `Snackbar` via `SnackbarHostState` (bottom center, Material defaults) |
| `LangToggle` | — | `🇺🇸 EN` / `🇲🇽 ES` button | current language | `btn border border-border text-xs px-2 py-1 font-mono text-text-muted hover:border-accent` | `SegmentedButton` / settings row "Idioma" |

---

## 3. Design tokens

Single **dark-only** theme. There are **zero `dark:` variants** in `src/` (grep) and no theme switch; `index.html` hard-codes `class="bg-bg-deep text-text-main"` (`index.html:9`). The only “theme” state is language.

### 3.1 Colors — exact values

Custom palette (declared twice: Tailwind `tailwind.config.js:6-29` and CSS vars `src/index.css:15-32`):

| Token | Hex | Where used |
|---|---|---|
| `bg-deep` / `--bg-deep` | **#0a0a0a** | app background, input track, progress track, magnet bar, footer bars |
| `bg-panel` / `--bg-panel` | **#0d0d0d** | sidebar, glass panels, modal body |
| `bg-card` / `--bg-card` | **#111111** | header background, buttons, scrollbar track |
| `bg-input` / `--bg-input` | **#0d0d0d** | text inputs/selects |
| `border` / `--border-color` | **#2a2a2a** | every 1 px border, scrollbar thumb |
| `border-focus` | **#ffc107** | hover border of `.btn`, `.queue-item` |
| `border-success` | **#4af626** | declared, not used in TSX (grep) |
| `accent` / `--accent` | **#ffc107** | primary actions, active nav, progress fill, links, focus ring |
| `accent-hover` / `--accent-hover` | **#ffd54f** | declared; used by `.status-scraping` (unused class) |
| `success` / `--success` | **#4af626** | complete status, active-local counter, success toast border |
| `danger` / `--danger` | **#ff2a2a** | errors, delete hover, failed status |
| `text-heading` / `--text-heading` | **#eaeaea** | h1/h2/h3, titles |
| `text-main` / `--text-main` | **#e0e0e0** | body/inputs |
| `text-muted` / `--text-muted` | **#888888** | secondary labels, timestamps, placeholders |

Inline/utility colors outside the palette:

| Where | Value | Note |
|---|---|---|
| `DownloadCard.tsx:117` | `#00a6fb` | Real-Debrid service tag (inline style class `text-[#00a6fb]`) |
| `SeasonDownloadModal.tsx:323,327,360` | `emerald-400` (#34d399) | success ticks / "Ya descargado" — Tailwind palette, not a token |
| `Sidebar.tsx:16-35`, `LogPanel.tsx:10-14` | `green-500` #22c55e, `yellow-500` #eab308, `red-500` #ef4444, `gray-600` #4b5563, `yellow-400` #facc15 | status dots / warn logs — Tailwind palette |
| `SearchTab.tsx:158`, `DownloadCard` warning text | `yellow-500`/`yellow-400` | partial-results banner |
| Body overlays | `rgba(0,0,0,0.15)` scanline lines, `rgba(0,0,0,0.6)`/`0.8` modal scrims | `index.css:51-78`; `SettingsModal.tsx:276`, `SeasonDownloadModal.tsx:336` |

Semantic mapping for Compose (`darkColorScheme`): `primary=#FFC107`, `onPrimary=#0A0A0A`, `background=#0A0A0A`, `onBackground=#E0E0E0`, `surface=#0D0D0D`, `onSurface=#EAEAEA`, `surfaceVariant=#111111`, `outline=#2A2A2A`, `error=#FF2A2A`, `tertiary/success=#4AF626` (Material has no success slot — define `AppColors.success`), `onSurfaceVariant=#888888`, secondary tag `#00A6FB`.

### 3.2 Typography

Loaded from Google Fonts (`index.html:7`): `Inter` weights **400, 700, 800, 900**; `JetBrains Mono` weights **400, 500, 700**.

| Token | Value |
|---|---|
| `font-display` | `['Inter', 'sans-serif']` (`tailwind.config.js:31`); CSS `--font-display: 'Inter', sans-serif` (`index.css:30`); applied on the app root (`App.tsx:250`) |
| `font-mono` | `['JetBrains Mono', 'monospace']` (`tailwind.config.js:32`); `--font-mono: 'JetBrains Mono', monospace` (`index.css:31`) |
| weights | body 400, bold 700, configured `extrabold 800`, `black 900` (`tailwind.config.js:34-37`); used: `font-bold`, `font-black` |
| sizes used | `[10px]`, `[11px]`, `text-xs` 12 px, `text-sm` 14 px, `text-base` 16 px, `text-xl` 20 px, `text-2xl` 24 px |
| `.btn` | 12 px / 700 / uppercase / `letter-spacing .05em` / mono (`index.css:110-127`) |
| `.input-field` | 12.8 px (`0.8rem`) mono; placeholder 11.2 px uppercase `letter-spacing .05em` (`index.css:209-225`) |
| `.status-badge` | 10 px / 700 / uppercase / `letter-spacing .1em` (unused in TSX) |
| section labels | `text-sm font-bold text-text-muted uppercase tracking-wider` (Logs/Transfers) and `text-accent font-mono text-sm uppercase tracking-widest` (Settings) |

### 3.3 Radius, shadows, spacing, motion

| Aspect | Value |
|---|---|
| Radius | **0 everywhere**: Tailwind `borderRadius.none = '0'` (`tailwind.config.js:38-40`), `.glass-panel{border-radius:0}` (`index.css:106`), no `rounded-*` classes in TSX except the spinner (`rounded-full`) and one `rounded` in `SeasonDownloadModal.tsx:373` (select) — those two are the only exceptions. |
| Shadows | Only `shadow-2xl` on Settings/Update panels (`SettingsModal.tsx:277`, `UpdateDialog.tsx:20`) and `box-shadow: 0 0 12px rgba(255,193,7,0.3)` on `.btn-play:hover` (unused) / `glow-pulse` keyframes (unused). No card elevation. |
| Spacing scale | Tailwind default 4 px base; recurring: `p-6` 24, `p-4` 16, `p-3` 12, `p-2` 8, `p-1.5` 6, `gap-1.5/2/3/4/6`, `space-y-2/4/6`, `px-6 py-3`, `h-1` 4 px, `w-64` 256 px, `max-w-md` 448 px, `max-w-2xl` 672 px, `max-h-[70vh]` / `[85vh]`, icon sizes 14/16/18/48. |
| Animations defined (`tailwind.config.js:41-77`) | `scanline` 8 s linear infinite; `noise` 0.5 s steps(2); `glow-pulse` 2 s ease-in-out; `slide-up` 0.4 s `cubic-bezier(0.16,1,0.3,1)`; `fade-in` 0.3 s ease-out. **Only `fade-in`, `slide-up`, `spin`, `pulse` are used** (grep); scanline/noise/glow-pulse are dead CSS. |
| Transitions | `.btn`: background/border/color 200 ms ease + transform 150 ms; hover `translateY(-2px)`; active `scale(0.97)`; `.queue-item` same; inputs 200 ms border/box-shadow; progress fills `transition-all duration-300`; no `transition: all` (explicitly avoided, `index.css:109`). |
| Focus | `.btn:focus-visible { outline: 2px solid #ffc107; outline-offset: 2px }` (`index.css:140-143`); inputs `border-color: accent; box-shadow: 0 0 0 1px accent` (`:226-229`); no global focus style elsewhere. |
| Selection / scrollbar | selection `background #ffc107; color #0a0a0a`; scrollbar 6 px, track `#111111`, thumb `#2a2a2a`, hover `#ffc107`, radius 0 (`index.css:81-99`). |
| CRT overlays | `body::before` scanlines: fixed inset 0, z-index **9999**, opacity **0.06**, `repeating-linear-gradient(0deg, transparent 0 2px, rgba(0,0,0,.15) 2px 4px)`; `body::after` noise: fixed inset 0, z-index **9998**, opacity **0.03**, inline SVG `feTurbulence baseFrequency 0.9`, tile 256 px, pointer-events none (`index.css:51-78`). Note they paint **above** modals (z-50) but are non-interactive. |

### 3.4 Layout metrics

| Element | Metric |
|---|---|
| Sidebar | fixed **256 px** (`w-64`, `Sidebar.tsx:48`), full height, `border-r`, `bg #0d0d0d`; brand block `p-6` with 32×32 accent square + status dot 10 px; nav `p-4 space-y-2`, items `px-4 py-3` full-width, 2 px active left border; status block `px-4 py-3`; bottom actions `p-4` |
| Header | `border-b p-6 bg #111`, no declared height (content: 24 px title line + 4 px gap + 20 px subtitle + 48 px padding ≈ **100 px**, computed not specified), right side two counters with 24 px gap |
| Magnet bar | `px-6 py-3`, 1 px bottom border, icon 18 px, input flex-1, 12 px gap |
| Content padding | `p-6` around every screen; screens stack `space-y-6` |
| Search form | `flex gap-4`; submit button next to a `ServiceSelector` |
| Result/card list | vertical `flex flex-col gap-3`; cards `p-4`, radius 0, 1 px border |
| Progress bar | `h-1` = **4 px** track with 1 px border, accent fill, 300 ms width transition |
| DownloadCard status grid | 2 columns, `p-2`, 10 px labels, 12 px values |
| Discover grid | 4 / 5 / 6 / 7 / 8 columns at Tailwind `sm 640 / md 768 / lg 1024 / xl 1280`; `gap-1.5` 6 px; poster `aspect-[2/3]`; rating badge 10 px top-right |
| Modals | Settings `max-w-2xl` 672 px, body `max-h-[70vh]`, `p-6`, `gap-8` sections; Season `max-w-2xl`, `max-h-[85vh]`; Update `max-w-md` 448 px, `p-8`; scrims `black/80` + `backdrop-blur-sm` (Settings/Update) vs `black/70` no blur (Season) |
| Toast | `bottom-6 right-6`, `p-4 px-6`, 4 px left border |
| Window | 1280×860 default, min 900×600, `backgroundColor #0a0a0a` (`main.ts:80-94`) |

Responsive behaviour: **only** the thumbnail grid changes with viewport. The shell (sidebar 256 px + main) has no breakpoints and the window cannot go below 900 px width (`main.ts:84`). Android must therefore treat layout as a redesign: `NavigationBar` <600 dp, `NavigationRail` ≥600 dp, content in a single column (see §6).

---

## 4. i18n

| Aspect | Value |
|---|---|
| Structure | `src/i18n/en.ts` (canonical, 157 lines), `src/i18n/es.ts` (`const es: TranslationKeys`, 158 lines), provider/hook in `src/i18n/index.tsx`, toggle `src/i18n/LangToggle.tsx` |
| Languages | `en`, `es` only; type `Lang = 'en' \| 'es'` (`i18n/index.tsx:5`) |
| Default | **Spanish** — `loadLang()` returns `'es'` when nothing stored (`i18n/index.tsx:24-30`); context default is `es` (`:18-22`) |
| Persistence | `localStorage` key **`tordownloader-lang`** (`i18n/index.tsx:8,36-38`); sets `document.documentElement.lang` (`:44-46`) |
| Key count | **127 leaf strings** per locale (verified by regex count), 10 namespaces: `nav, header, magnetBar, search, transfers, downloadCard (incl. nested phases), logs, settings, toasts, update` |
| Interpolation | one manual placeholder: `pluginsUpdated: 'Updated {count} plugin(s)'` replaced with `.replace('{count}', n)` (`SettingsModal.tsx:154`) — no i18n library |
| Error translation | `translateError`: messages containing `infringing file` / `infringing_file` → `Infringing File` / `Eliminado por Copyright` (`i18n/index.tsx:64-77`); used for add failures |
| Toggle UI | `🇺🇸 EN` when current is ES, `🇲🇽 ES` when current is EN (`LangToggle.tsx:12`), title text switches language |
| Not localized | Discover UI is **hard-coded Spanish** outside a few strings: `TMDB API Key requerido`, `Cargando catálogo...`, `Reintentar`, `← Volver al catálogo`, `Buscar en TMDB...`, `Películas`, `Series`, `Tendencias`, `Populares`, `Mejor Valoradas`, `Sin resultados`, `Descargar Temporada N`, `Resolviendo episodios...`, `Temporadas`, `Sin episodios`, `Ya descargado`, `Ya en la cola de transferencias`, `Omitir/Incluir`, `Cerrar`, `Descargar`, `Agregando...`, `Reintentar fallidos (N)`, `Elegir otro resultado y toca Reintentar`, `No encontrado`, `Descarga agregada`, `Eliminado por Copyright` (`DiscoverView.tsx:313,327,340,356,441,451,459,505,520,531`, `DiscoverResults.tsx:32,80,118,161`, `SeasonDownloadModal.tsx:309,340,347,351,358,360,362,368,392,401,405,420,423,425`); several Settings strings are also hard-coded ES (`SettingsModal.tsx:192,194,394,404,409`). Plan: ES is the product default, so fold all of these into a single `strings.xml`/`values-es` from day one. |

Representative samples (EN / ES):

| Key | EN | ES |
|---|---|---|
| `nav.transfers` | Transfers | Transferencias |
| `header.title` | Download Control | Panel de Control |
| `search.placeholder` | QUERY TARGET... (e.g. Ubuntu 22.04) | BUSCAR... (ej. Ubuntu 22.04) |
| `search.acquire` | Acquire | Adquirir |
| `downloadCard.phases.magnet_conversion` | Converting Magnet | Convirtiendo Magnet |
| `downloadCard.phases.waiting_files_selection` | Awaiting File Selection | Selección de Archivos |
| `transfers.clearCompleted` | Clear Completed | Limpiar Completados |
| `settings.enginesBundled` | 17 engines bundled: TPB, EZTV, … | 17 motores incluidos: TPB, EZTV, … |
| `toasts.invalidInput` | Invalid input. Paste a magnet link (magnet:?...) or a .torrent URL (https://...) | Entrada inválida. Pegue un enlace magnet (magnet:?...) o una URL .torrent (https://...) |
| `update.ready` | Update ready! Restart to install. | ¡Actualización lista! Reinicia para instalar. |

---

## 5. Accessibility and interaction notes (fix during redesign)

1. Touch targets: cancel/delete buttons are 32×32 px (`DownloadCard.tsx:126,138`) — below 48 dp; several `text-[10px]` labels are below comfortable mobile size.
2. Icon-only controls use glyphs `⏹`, `X`, `✕`, `×`, `🔍`, `🏜️`, `⚠️`, `✓`, `⧉`, `✗`; only the two DownloadCard buttons have `aria-label` (`:129,141`). Compose needs `contentDescription` for every icon/emoji.
3. Status is conveyed by color + text (fine) but sometimes only color (FlareSolverr dot, level colors in logs).
4. No keyboard/focus management in modals; no Escape handler; no focus trap (matters for TV/Chromebook targets).
5. Contrast: `#888888` on `#0a0a0a` is the weakest body/label pair; verify against WCAG AA on device (muted is used for 10–12 px text). Accent `#ffc107` on `#0a0a0a` and danger `#ff2a2a` on `#0a0a0a` are strong.
6. Fixed desktop shell cannot reflow; `h-screen overflow-hidden` means any overflow is scrolled inside inner panels only (`App.tsx:250,277`).
7. `hover:` styles (lift, border highlight) have no touch equivalent — replace with pressed state/ripple or drop.

---

## 6. Web idiom → Compose equivalents

| Web idiom (evidence) | Compose replacement | Notes |
|---|---|---|
| Fixed 256 px sidebar + main column (`Sidebar.tsx:48`, `App.tsx:250-261`) | `NavigationBar` (<600 dp) / `NavigationRail` (≥600 dp) + `Scaffold`; destinations Search, Transfers, Discover, Logs; Settings as top-bar action | Do **not** try to keep the sidebar; phone width makes it unusable |
| `activeNav` string state (`App.tsx:35`) | `NavHost` routes `search/transfers/logs/discover/settings` + `selectedItem` | Settings becomes a route, not a modal |
| Header with two counters (`Header.tsx:13-27`) | `TopAppBar` title/subtitle + two `Badge`/`AssistChip` counters, or a custom header `Row` | Keep 24 px title, 12 px mono subtitle |
| `glass-panel` (`index.css:103-107`) | `Surface(color = #0D0D0D, border = BorderStroke(1.dp, #2A2A2A), shape = RectangleShape)` | Zero radius everywhere; use `RectangleShape` consistently |
| `.btn` / `.btn-accent` (`index.css:110-154`) | `OutlinedButton`/`TextButton` with `ButtonDefaults` colors (accent border/text, accent fill on pressed/selected), uppercase mono 12 sp, 10/20 dp padding | Drop `translateY(-2px)` hover; keep 97 % press scale if desired via `interactionSource` |
| `input-field`, `select-field` (`index.css:209-249`) | `OutlinedTextField`/`ExposedDropdownMenuBox` with 1 dp outline #2A2A2A, focused accent, 0 dp shape, mono typography | placeholder uppercase 11 sp |
| Search query tabs (`SearchTabsBar.tsx`) | `ScrollableTabRow` or `LazyRow` of `FilterChip`s with `Icons.Close`; state kept in a `searchTabs` ViewModel/`SavedStateHandle` | One search session per tab; events tagged with tab id (the `tabId` fix already in tree) |
| Per-engine progress text (`SearchTab.tsx:118-129`) | `LinearProgressIndicator` (indeterminate) + text "Buscando en «engine»" | Events: `engine_start` / `engine_results` / `done` |
| Progress bar (`DownloadCard.tsx:152-154`) | `LinearProgressIndicator(progress = p/100f, color = accent, trackColor = #0A0A0A, height = 4.dp)` | 0–99 % while active, 100 only when complete — preserve exactly |
| Radix/dialog overlays (custom in this app) | `Dialog`/`AlertDialog` for Update; full-screen route for Settings/Season | Settings form is 500+ px tall — use a scrollable screen with sticky bottom bar |
| Toast (`Toast.tsx`) | `Snackbar` + `SnackbarHostState` | 3 s timeout (matches `App.tsx:46`) |
| Bottom-right fixed toast | centered/bottom `Snackbar` | Platform convention over exact position |
| `shell.openPath` "Open Dest" | `ACTION_VIEW` on tree URI / in-app file list | No "open Explorer" equivalent |
| Folder browse buttons (`SettingsModal.tsx:429,442,455`) | `ActivityResultContracts.OpenDocumentTree` + persist permission | Three folders on desktop → recommend one tree root + subpaths on Android |
| Language toggle (`LangToggle.tsx`) | Settings row with `SegmentedButton` ES/EN; `AppCompatDelegate.setApplicationLocales` or in-app `CompositionLocal` | Default locale **es** |
| Monospace log list (`LogPanel.tsx:59-76`) | `LazyColumn` with `FontFamily.Monospace` 11 sp, timestamp 40 % alpha | Cap 500 entries, autoscroll |
| Poster grid (`DiscoverThumbnails.tsx:11`) | `LazyVerticalGrid(GridCells.Adaptive(104.dp))`, `aspectRatio(2f/3f)`, rating badge overlay | 4–8 columns mapped to phone/tablet adaptive |
| TMDB/Comet poster `<img>` | Coil `AsyncImage` | Add placeholder `?` tile |
| CRT scanlines/noise (`index.css:51-78`) | **Drop** | Battery/perf cost, no design value on mobile; optionally a static subtle overlay |
| Custom 6 px scrollbar | Platform scrollbars | No custom scrollbar |
| `animate-fade-in` / `slide-up` | `AnimatedVisibility` with fade/slide | 300 ms / 400 ms `FastOutSlowInEasing`-ish; Compose default spring acceptable |
| Hover lift on `.btn`/cards | `Modifier.pointerHoverIcon` + `animateFloatAsState` elevation — optional | Touch has no hover; use ripple/pressed state |

---

## 7. Screen-flow notes for a Compose implementation

1. **Search** is the launch screen (`App.tsx:35` `.default 'search'`). It contains three stacked controls (magnet bar at top, query form, tab bar, results) — on a phone, move the magnet input into a FAB/sheet to avoid three fixed rows.
2. **Transfers** is a flat list (no grouping/sorting in UI); newest first comes from SQL (`created_at DESC`). Cards are dense (title, meta, status, progress, two status cells) — keep as a single `ElevatedCard`-less `Surface`.
3. **Discover** is a three-level drill-down (catalog → detail → season modal) that currently lives in one component with local state; on Android make it a nested nav graph so back works.
4. **Settings** is the only long form; all credentials live there. On Android split into `Settings` → "Cuentas" / "Almacenamiento" / "Idioma y actualizaciones" sub-screens, and never display stored token values (only "Vinculada").
5. **Logs** is a debug screen; gate it behind a build-type or a hidden tap on the version label (desktop exposes it in the sidebar).
6. The **UpdateDialog** disappears with the Electron updater (§`source-analysis.md` 7.1); if Play in-app updates are used, there is no dialog at all — the Play UI handles it.

*End of UI inventory.*