package com.ddownloader.android.ui

import com.ddownloader.android.core.LogL10n
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.ddownloader.android.api.CatalogAddon
import com.ddownloader.android.api.CometApi
import com.ddownloader.android.api.TmdbApi
import com.ddownloader.android.api.TorBoxApi
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.BuildConfig
import com.ddownloader.android.data.AccountAuth
import com.ddownloader.android.data.DeviceAuth
import com.ddownloader.android.data.UpdateChecker
import com.ddownloader.android.data.UpdateInstaller
import com.ddownloader.android.data.ServiceLocator
import com.ddownloader.android.data.db.DownloadRecord
import com.ddownloader.android.data.db.TransferState
import com.ddownloader.android.download.DownloadManager
import com.ddownloader.android.download.DownloadService
import com.ddownloader.android.download.ResumeWorker
import com.ddownloader.android.storage.MediaLayout

private const val MAX_FILES_PER_TORRENT = 40

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val locator = ServiceLocator

    val settings = locator.settings.settings
    // Copy for user-facing messages follows the persisted language, so a toast raised from the
    // ViewModel is in the same language as the screen that triggered it.
    private val str get() = com.ddownloader.android.ui.i18n.stringsFor(
        com.ddownloader.android.ui.i18n.Language.fromTag(settings.value.language),
    )

    init {
        // Log lines are written outside the composition, so they follow the setting through
        // LogL10n rather than LocalStrings.
        viewModelScope.launch {
            settings.collect {
                com.ddownloader.android.core.LogL10n.language =
                    com.ddownloader.android.ui.i18n.Language.fromTag(it.language)
            }
        }
    }

    val records: StateFlow<List<DownloadRecord>> = locator.db.records
    val live: StateFlow<Map<String, DownloadManager.Live>> = locator.manager.live
    val activeCount: StateFlow<Int> = locator.manager.activeCount
    val logs = AppLog.entries

    // Token authorization state per debrid provider (see data/AccountAuth.kt)
    val torboxAccount: StateFlow<AccountAuth.AccountState> = locator.accounts.torboxState
    val realDebridAccount: StateFlow<AccountAuth.AccountState> = locator.accounts.realdebridState
    val logErrors = AppLog.errorCount

    // ── Transient UI state ────────────────────────────────────────────────
    var searchQuery by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searchResults by mutableStateOf<List<TmdbApi.Item>>(emptyList())
    var searchError by mutableStateOf<String?>(null)

    var streamSheetItem by mutableStateOf<TmdbApi.Item?>(null)
    var streams by mutableStateOf<List<CometApi.Stream>>(emptyList())
    var streamsLoading by mutableStateOf(false)
    var streamsError by mutableStateOf<String?>(null)

    var pastedLink by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    var logFilter by mutableStateOf("ALL")

    // Discover (TMDB catalogue → Comet streams)
    var discoverItems by mutableStateOf<List<TmdbApi.Item>>(emptyList())
    var discoverLoading by mutableStateOf(false)
    var discoverError by mutableStateOf<String?>(null)
    var discoverKind by mutableStateOf("movie")
    /** "tmdb" (list shelves) or "catalog" (a Stremio catalog add-on) — same split as the Windows app. */
    var discoverSource by mutableStateOf("tmdb")
    var catalogues by mutableStateOf<List<CatalogAddon.Catalog>>(emptyList())
    var catalogLoading by mutableStateOf(false)
    var catalogError by mutableStateOf<String?>(null)
    var selectedCatalogue by mutableStateOf<CatalogAddon.Catalog?>(null)
    var catalogueItems by mutableStateOf<List<CatalogAddon.Item>>(emptyList())

    // Shelf-based Discover (many categories, loaded in parallel)
    data class Shelf(val title: String, val items: List<TmdbApi.Item>)

    var shelves by mutableStateOf<List<Shelf>>(emptyList())
    var shelvesLoading by mutableStateOf(false)
    var shelvesError by mutableStateOf<String?>(null)

    fun toast(text: String) {
        message = text
    }

    fun consumeMessage() {
        message = null
    }

    // ── Debrid account linking (URL + code device flow) ───────────────────
    sealed interface LinkUi {
        data object Idle : LinkUi
        data class Starting(val provider: DeviceAuth.Provider) : LinkUi
        data class Waiting(
            val prompt: DeviceAuth.Prompt,
            val secondsLeft: Int,
        ) : LinkUi
        data class Linked(val provider: DeviceAuth.Provider, val account: String) : LinkUi
        data class Failed(val provider: DeviceAuth.Provider, val message: String) : LinkUi
    }

    private val _linkState = MutableStateFlow<LinkUi>(LinkUi.Idle)
    val linkState: StateFlow<LinkUi> = _linkState.asStateFlow()
    private var linkJob: Job? = null

    /**
     * Starts the provider's device authorization: fetch a code, show it, and poll until
     * the user authorizes. The credential that comes back is stored encrypted; the UI
     * never sees it.
     */
    fun linkAccount(provider: DeviceAuth.Provider) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            _linkState.value = LinkUi.Starting(provider)
            val prompt = locator.deviceAuth.start(provider).getOrElse { e ->
                _linkState.value = LinkUi.Failed(provider, e.message ?: "could not start pairing")
                return@launch
            }
            val started = System.currentTimeMillis()
            _linkState.value = LinkUi.Waiting(prompt, prompt.expiresInSeconds)
            val deadline = started + prompt.expiresInSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(prompt.intervalSeconds * 1000L)
                when (val outcome = locator.deviceAuth.poll(prompt)) {
                    is DeviceAuth.Outcome.Pending -> {
                        val left = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt()
                        _linkState.value = LinkUi.Waiting(prompt, left)
                    }
                    is DeviceAuth.Outcome.Linked -> {
                        // Persist the credential (encrypted) and the refresh material.
                        when (outcome.provider) {
                            DeviceAuth.Provider.TORBOX -> locator.settings.setTorboxToken(outcome.token)
                            DeviceAuth.Provider.REALDEBRID -> {
                                locator.settings.setRealDebridToken(outcome.token)
                                if (outcome.clientId != null && outcome.clientSecret != null) {
                                    locator.settings.setRealDebridOauth(
                                        outcome.clientId,
                                        outcome.clientSecret,
                                        outcome.refreshToken.orEmpty(),
                                    )
                                }
                            }
                        }
                        locator.settings.setOnboardingCompleted(
                            locator.settings.current().onboardingCompleted,
                        )
                        _linkState.value = LinkUi.Linked(outcome.provider, outcome.account)
                        locator.accounts.refresh()
                        return@launch
                    }
                    is DeviceAuth.Outcome.Failed -> {
                        _linkState.value = LinkUi.Failed(provider, outcome.reason)
                        return@launch
                    }
                }
            }
            _linkState.value = LinkUi.Failed(provider, "The code expired before the account was authorized.")
        }
    }

    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        _linkState.value = LinkUi.Idle
    }

    fun unlink(provider: DeviceAuth.Provider) {
        viewModelScope.launch {
            when (provider) {
                DeviceAuth.Provider.TORBOX -> locator.settings.setTorboxToken("")
                DeviceAuth.Provider.REALDEBRID -> locator.settings.setRealDebridToken("")
            }
            locator.accounts.refresh()
            toast(str.unlinkedItem.format(provider.label))
        }
    }

    // ── Self-update ───────────────────────────────────────────────────────
    sealed interface UpdateUi {
        data object Idle : UpdateUi
        data object Checking : UpdateUi
        data class Available(val release: UpdateChecker.AvailableRelease) : UpdateUi
        data class Downloading(val bytes: Long, val total: Long) : UpdateUi
        data class Ready(
            val release: UpdateChecker.AvailableRelease,
            val apk: java.io.File,
            val sha256: String,
        ) : UpdateUi
        /** Verified APK sitting in the public Download folder, waiting for the user to tap it. */
        data class Saved(val path: String) : UpdateUi
        data class Error(val message: String) : UpdateUi
    }

    val currentVersion: String = BuildConfig.VERSION_NAME
    private val _updateState = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val updateState: StateFlow<UpdateUi> = _updateState.asStateFlow()

    /**
     * Checks GitHub Releases. Auto-checks are throttled to once every 6 h and skipped
     * for a version the user already dismissed; a manual check always runs.
     */
    fun checkForUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            val cfg = settings.value
            val fresh = System.currentTimeMillis() - cfg.lastUpdateCheckAt < 6 * 60 * 60 * 1000L
            if (!manual && fresh) return@launch
            _updateState.value = UpdateUi.Checking
            val result = locator.updateChecker.check(currentVersion)
            locator.settings.setLastUpdateCheckAt(System.currentTimeMillis())
            _updateState.value = when (result) {
                is UpdateChecker.Result.UpToDate -> {
                    if (manual) toast(str.latestVersion.format(currentVersion))
                    UpdateUi.Idle
                }
                is UpdateChecker.Result.Available -> {
                    if (result.release.version == cfg.dismissedUpdateVersion) {
                        UpdateUi.Idle
                    } else {
                        UpdateUi.Available(result.release)
                    }
                }
                is UpdateChecker.Result.Failed -> {
                    if (manual) toast(str.updateCheckFailed.format(result.reason))
                    UpdateUi.Idle
                }
            }
        }
    }

    fun downloadUpdate() {
        val current = _updateState.value
        val release = when (current) {
            is UpdateUi.Available -> current.release
            is UpdateUi.Ready -> current.release
            else -> return
        }
        viewModelScope.launch {
            _updateState.value = UpdateUi.Downloading(0, release.sizeBytes)
            when (val res = locator.updateInstaller.download(release) { bytes, total ->
                _updateState.value = UpdateUi.Downloading(bytes, total)
            }) {
                is UpdateInstaller.DownloadResult.Ready ->
                    _updateState.value = UpdateUi.Ready(release, res.apk, res.sha256)
                is UpdateInstaller.DownloadResult.Failed -> {
                    _updateState.value = UpdateUi.Error(res.reason)
                    toast(str.updateFailed.format(res.reason))
                }
            }
        }
    }

    /**
     * Saves the verified APK to the public Download folder. Installing is the user's next step, done
     * from their file manager — the app no longer asks for the install-packages permission, which is
     * what made the system's scanner treat the whole app as fake software.
     */
    fun saveUpdateToDownloads() {
        val ready = _updateState.value as? UpdateUi.Ready ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                locator.updateInstaller.saveToDownloads(ready.apk, ready.release.version)
            }
            result
                .onSuccess {
                    _updateState.value = UpdateUi.Saved(it)
                    toast(str.updateSaved.format(it))
                }
                .onFailure { toast(str.updateFailed.format(it.message ?: "")) }
        }
    }

    /** "Later": remember this version so the prompt does not reappear every launch. */
    fun laterUpdate() {
        val version = when (val s = _updateState.value) {
            is UpdateUi.Available -> s.release.version
            is UpdateUi.Ready -> s.release.version
            is UpdateUi.Downloading -> null
            else -> null
        }
        viewModelScope.launch {
            if (version != null) locator.settings.setDismissedUpdateVersion(version)
            _updateState.value = UpdateUi.Idle
        }
    }

    // ── Session ───────────────────────────────────────────────────────────
    /** Called when the UI becomes visible: re-arm the durable queue. */
    fun onUiVisible() {
        ResumeWorker.schedule(getApplication())
        viewModelScope.launch { locator.accounts.refresh() }
        checkForUpdate(manual = false)
        viewModelScope.launch {
            val resumed = locator.manager.resumeInterrupted()
            if (resumed > 0) {
                DownloadService.start(getApplication())
                toast(str.resumeInterrupted.format(resumed))
            }
        }
    }

    // ── Search / streams ──────────────────────────────────────────────────
    fun search() {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            searching = true
            searchError = null
            searchResults = emptyList()
            try {
                if (!settings.value.hasTmdb) {
                    searchError = "Add a TMDB API key in Settings to search, or paste a link below."
                } else {
                    val items = locator.tmdb.search(q)
                    searchResults = items
                    if (items.isEmpty()) searchError = "No results for \"$q\"."
                }
            } catch (t: Throwable) {
                AppLog.e("Pipeline", LogL10n.of("addMagnet failed: ${t.javaClass.simpleName}: ${t.message}", "addMagnet falló: ${t.javaClass.simpleName}: ${t.message}"), t)
                searchError = t.message ?: "Search failed"
            } finally {
                searching = false
            }
        }
    }

    /** Loads every shelf for [kind] in parallel; one failing shelf never blanks the page. */
    fun loadShelves(kind: String) {
        viewModelScope.launch {
            shelvesLoading = true
            shelvesError = null
            shelves = emptyList()
            try {
                val specs = locator.tmdb.rowSpecs(kind)
                val loaded = coroutineScope {
                    specs.map { spec ->
                        async { Shelf(spec.title, locator.tmdb.rowItems(spec, kind)) }
                    }.awaitAll()
                }
                shelves = loaded.filter { it.items.isNotEmpty() }
                if (shelves.isEmpty()) {
                    shelvesError = "TMDB returned no titles. Check your API key in Settings."
                }
            } catch (t: Throwable) {
                AppLog.e("Pipeline", LogL10n.of("addMagnet failed: ${t.javaClass.simpleName}: ${t.message}", "addMagnet falló: ${t.javaClass.simpleName}: ${t.message}"), t)
                shelvesError = t.message ?: "Discover failed"
            } finally {
                shelvesLoading = false
            }
        }
    }

    fun loadDiscover(kind: String) {
        viewModelScope.launch {
            discoverLoading = true
            discoverError = null
            try {
                val items = locator.tmdb.trending(kind)
                discoverItems = items
                if (items.isEmpty()) discoverError = "TMDB returned no titles (check the API key)."
            } catch (t: Throwable) {
                AppLog.e("Pipeline", LogL10n.of("addMagnet failed: ${t.javaClass.simpleName}: ${t.message}", "addMagnet falló: ${t.javaClass.simpleName}: ${t.message}"), t)
                discoverError = t.message ?: "Discover failed"
            } finally {
                discoverLoading = false
            }
        }
    }

    /** Reads the add-on manifest and lists its catalogs (empty for the embedded Comet share). */
    fun loadCatalogues() {
        viewModelScope.launch {
            catalogLoading = true
            catalogError = null
            // Resolve the add-on base here: the locator's init cannot read settings (suspend).
            locator.catalog.setBase(
                settings.value.catalogAddonUrl.ifBlank { BuildConfig.COMET_BASE },
            )
            locator.catalog.catalogs()
                .onSuccess { list ->
                    catalogues = list
                    selectedCatalogue = list.firstOrNull()
                    list.firstOrNull()?.let { selectCatalogue(it) }
                }
                .onFailure { catalogError = it.message }
            catalogLoading = false
        }
    }

    fun selectCatalogue(catalog: CatalogAddon.Catalog) {
        selectedCatalogue = catalog
        viewModelScope.launch {
            catalogLoading = true
            catalogError = null
            locator.catalog.items(catalog.type, catalog.id)
                .onSuccess { catalogueItems = it }
                .onFailure { catalogError = it.message }
            catalogLoading = false
        }
    }

    /** Catalog items already carry an IMDb id, so the stream search needs no TMDB lookup. */
    fun openCatalogueItem(item: CatalogAddon.Item) {
        openItem(
            TmdbApi.Item(
                id = 0,
                title = item.name,
                year = item.year,
                overview = item.description.orEmpty(),
                posterPath = null,
                imdbId = item.id,
                mediaType = if (item.type == "series") "tv" else "movie",
                voteAverage = item.rating ?: 0.0,
            ),
        )
    }

    fun setCatalogueAddon(url: String) {
        viewModelScope.launch {
            locator.settings.setCatalogAddonUrl(url)
            locator.catalog.setBase(url.ifBlank { BuildConfig.COMET_BASE })
            loadCatalogues()
        }
    }

    fun openItem(item: TmdbApi.Item) {
        streamSheetItem = item
        streams = emptyList()
        streamsError = null
        streamsLoading = true
        viewModelScope.launch {
            try {
                val imdb = item.imdbId ?: locator.tmdb.imdbId(item.id, item.mediaType)
                if (imdb.isNullOrBlank()) {
                    streamsError = "No IMDb id for this title — Comet cannot resolve streams."
                    return@launch
                }
                val type = if (item.mediaType == "tv" || item.mediaType == "series") "series" else "movie"
                // Series need season/episode; default to S01E01 when the caller has
                // no episode context (the Downloads flow re-resolves per episode).
                // Web.get() blocks; keep it off the main thread like the TMDB calls.
                val result = withContext(Dispatchers.IO) {
                    Result.success(
                            locator.sources.resolve(
                                type = type,
                                imdbId = imdb,
                                season = if (type == "series") 1 else null,
                                episode = if (type == "series") 1 else null,
                                title = item.title,
                                year = item.year,
                            )
                        )
                }
                result.onSuccess { streams = it }
                    .onFailure { streamsError = it.message ?: "Comet request failed" }
                if (streams.isEmpty() && streamsError == null) {
                    streamsError = "Comet returned no streams for this title."
                }
            } finally {
                streamsLoading = false
            }
        }
    }

    fun closeStreamSheet() {
        streamSheetItem = null
        streams = emptyList()
        streamsError = null
    }

    /** Queue a Comet stream. Debrid is required to turn an infoHash into bytes. */
    fun downloadStream(stream: CometApi.Stream) {
        AppLog.i("Pipeline", LogL10n.of("downloadStream tapped: name=${stream.name} size=${stream.videoSize}", "downloadStream pulsado: name=${stream.name} size=${stream.videoSize}"))
        val magnet = stream.magnet()
        if (magnet == null) {
            toast(str.noInfoHash)
            return
        }
        if (!settings.value.hasAnyDebrid) {
            toast(str.noDebrid)
            return
        }
        AppLog.i("Pipeline", LogL10n.of("magnet built (${magnet.length} chars) — handing to the debrid flow", "magnet creado (${magnet.length} caracteres) — pasando al flujo debrid"))
        toast(str.magnetResolved)
        addMagnet(magnet, estimatedSize = stream.videoSize ?: 0L, name = stream.displayName())
        closeStreamSheet()
    }

    // ── Queue actions ─────────────────────────────────────────────────────
    fun addUrl(url: String, name: String? = null, expectedSize: Long = 0L) {
        // A pasted magnet is a first-class input for a debrid client.
        if (url.trim().startsWith("magnet:", ignoreCase = true)) {
            addMagnet(url.trim(), estimatedSize = expectedSize, name = name ?: "magnet")
            return
        }
        val clean = url.trim()
        if (clean.isEmpty()) return
        // Input validation: only http(s), a real host, and no embedded credentials
        // (userinfo in a URL would be silently sent to the file host).
        val parsed = runCatching { java.net.URI(clean) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            toast(str.onlyHttps)
            return
        }
        if (parsed?.userInfo != null || parsed.host.isNullOrBlank()) {
            toast(str.invalidUrl)
            return
        }
        val record = DownloadRecord(
            url = clean,
            name = name ?: clean.substringAfterLast('/').substringBefore('?').ifBlank { "download" },
            expectedSize = expectedSize,
            service = "direct",
            state = TransferState.QUEUED,
        )
        locator.manager.enqueue(record)
        DownloadService.start(getApplication())
        toast(str.queuedItem.format(record.name))
    }

    fun addMagnet(magnet: String, estimatedSize: Long = 0L, name: String = "magnet") {
        AppLog.i("Pipeline", LogL10n.of("addMagnet: start (hasDebrid=${settings.value.hasAnyDebrid}, torbox=${settings.value.hasTorBox})", "addMagnet: inicio (hasDebrid=${settings.value.hasAnyDebrid}, torbox=${settings.value.hasTorBox})"))
        if (!settings.value.hasAnyDebrid) {
            toast(str.noDebrid)
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                if (settings.value.hasTorBox) {
                    val id = locator.torbox.addMagnet(magnet).getOrThrow()
                    AppLog.i("Pipeline", LogL10n.of("torbox createtorrent accepted, id=$id", "torbox aceptó createtorrent, id=$id"))
                    // TorBox needs a few seconds to fetch metadata. Polling beats giving up:
                    // the old single-shot lookup told the user "file list not ready yet".
                    var files: List<TorBoxApi.TorrentFile> = emptyList()
                    var torrentId: String = id
                    for (attempt in 0 until 25) {
                        val torrents = locator.torbox.listTorrents().getOrNull().orEmpty()
                        val t = torrents.firstOrNull { it.id == id }
                        if (t != null && !t.files.isNullOrEmpty()) {
                            files = t.files
                            torrentId = t.id
                            AppLog.i("Pipeline", LogL10n.of("torbox files ready after ${attempt * 3}s: ${files.size} file(s)", "archivos de torbox listos tras ${attempt * 3}s: ${files.size} archivo(s)"))
                            break
                        }
                        delay(3000)
                    }
                    if (files.isEmpty()) {
                        AppLog.w("Pipeline", LogL10n.of("torbox never listed files for id=$id", "torbox nunca listó archivos para id=$id"))
                        toast(str.magnetNoFiles.format(id))
                        return@launch
                    }
                    // Queue every video file, not just the biggest one: season packs are
                    // the normal case for series and used to silently yield one episode.
                    val videos = files
                        .filter { it.size >= 20L * 1024 * 1024 }
                        .ifEmpty { files }
                        .sortedByDescending { it.size }
                        .take(MAX_FILES_PER_TORRENT)
                    videos.forEach { file ->
                        locator.manager.enqueue(
                            DownloadRecord(
                                url = "",
                                name = file.name.substringAfterLast('/'),
                                expectedSize = file.size,
                                service = "torbox",
                                remoteId = torrentId,
                                fileId = file.id,
                                state = TransferState.QUEUED,
                            )
                        )
                    }
                    AppLog.i("Pipeline", LogL10n.of("enqueued ${videos.size} file(s) from torrent $torrentId", "encolados ${videos.size} archivo(s) del torrent $torrentId"))
                    DownloadService.start(getApplication())
                    toast(
                        if (videos.size == 1) "Queued 1 file from TorBox"
                        else "Queued ${videos.size} files from TorBox",
                    )
                } else {
                    val id = locator.realdebrid.addMagnet(magnet).getOrThrow()
                    locator.manager.enqueue(
                        DownloadRecord(
                            url = "",
                            name = name,
                            expectedSize = estimatedSize,
                            service = "realdebrid",
                            remoteId = id,
                            state = TransferState.QUEUED,
                        )
                    )
                    DownloadService.start(getApplication())
                    toast(str.queuedFromRd)
                }
            } catch (t: Throwable) {
                AppLog.e("Pipeline", LogL10n.of("addMagnet failed: ${t.javaClass.simpleName}: ${t.message}", "addMagnet falló: ${t.javaClass.simpleName}: ${t.message}"), t)
                toast(str.couldNotAddMagnet.format(t.message ?: ""))
            } finally {
                busy = false
            }
        }
    }

    fun pause(id: String) = locator.manager.pause(id)
    fun resume(id: String) = locator.manager.start(id)
    fun cancel(id: String) = locator.manager.cancel(id)
    /** Deletes the row (and any partial file) — what the Remove button must do. */
    fun remove(id: String) = locator.manager.remove(id)
    fun retry(id: String) = locator.manager.retry(id)

    fun clearCompleted() {
        locator.manager.clearCompleted()
        toast(str.clearedFinished)
    }

    // ── Settings ──────────────────────────────────────────────────────────
    /**
     * Debrid auth is TOKEN auth: the pasted API token is the credential. Storing it
     * is not the same as being authorized, so it is proved against the provider's
     * authenticated endpoint and the outcome is reported.
     */
    fun saveTorboxToken(token: String) {
        viewModelScope.launch {
            locator.settings.setTorboxToken(token)
            if (token.isBlank()) {
                locator.accounts.refresh()
                toast(str.tokenCleared)
                return@launch
            }
            when (val s = locator.accounts.validateTorBox()) {
                is AccountAuth.AccountState.Authorized -> toast(str.authorizedAs.format("TorBox", s.account, s.detail))
                is AccountAuth.AccountState.Rejected -> toast(str.rejectedToken.format("TorBox", s.reason))
                is AccountAuth.AccountState.Unverified -> toast(str.tokenSavedUnverified.format(s.reason))
                else -> toast(str.tokenSaved)
            }
        }
    }

    fun saveRealDebridToken(token: String) {
        viewModelScope.launch {
            locator.settings.setRealDebridToken(token)
            if (token.isBlank()) {
                locator.accounts.refresh()
                toast(str.rdTokenCleared)
                return@launch
            }
            when (val s = locator.accounts.validateRealDebrid()) {
                is AccountAuth.AccountState.Authorized -> toast(str.authorizedAs.format("Real-Debrid", s.account, s.detail))
                is AccountAuth.AccountState.Rejected -> toast(str.rejectedToken.format("Real-Debrid", s.reason))
                is AccountAuth.AccountState.Unverified -> toast(str.tokenSavedUnverified.format(s.reason))
                else -> toast(str.tokenSaved)
            }
        }
    }

    /** Re-checks both providers and reports a one-line status. */
    fun checkAccounts() {
        viewModelScope.launch {
            locator.accounts.refresh()
            fun describe(s: AccountAuth.AccountState) = when (s) {
                is AccountAuth.AccountState.Authorized -> "authorized (${s.account})"
                is AccountAuth.AccountState.Rejected -> "token rejected"
                is AccountAuth.AccountState.Unverified -> "unverified"
                AccountAuth.AccountState.Checking -> "checking…"
                AccountAuth.AccountState.NotConfigured -> "not configured"
            }
            toast(
                "TorBox: ${describe(locator.accounts.torboxState.value)} • " +
                    "Real-Debrid: ${describe(locator.accounts.realdebridState.value)}",
            )
        }
    }

    /**
     * Setup is mandatory: at least one linked debrid account **and** a validated TMDB
     * key. The assistant also disables its buttons, but the rule is enforced here so a
     * stale UI state cannot skip it.
     */
    fun completeOnboarding() {
        viewModelScope.launch {
            val cfg = locator.settings.current()
            if (!cfg.hasAnyDebrid) {
                toast(str.needDebridToContinue)
                return@launch
            }
            if (!cfg.hasTmdb) {
                toast(str.needTmdbToContinue)
                return@launch
            }
            locator.settings.setOnboardingCompleted(true)
            toast(str.setupComplete)
        }
    }

    /** Observable result of "Validate & save" so the button has visible feedback. */
    sealed interface TmdbUi {
        data object Idle : TmdbUi
        data object Checking : TmdbUi
        data object Valid : TmdbUi
        data class Invalid(val reason: String) : TmdbUi
    }

    private val _tmdbKeyState = MutableStateFlow<TmdbUi>(TmdbUi.Idle)
    val tmdbKeyState: StateFlow<TmdbUi> = _tmdbKeyState.asStateFlow()

    fun saveTmdbKey(key: String) {
        viewModelScope.launch {
            // Pasting a key often drags in a newline or spaces; TMDB answers 401 for those,
            // which looks like "wrong key" when it is really "trailing whitespace".
            val cleaned = key.filterNot { it.isWhitespace() }
            if (cleaned.isBlank()) {
                locator.settings.setTmdbApiKey("")
                _tmdbKeyState.value = TmdbUi.Idle
                toast(str.tmdbKeyCleared)
                return@launch
            }
            _tmdbKeyState.value = TmdbUi.Checking
            val code = withContext(Dispatchers.IO) { locator.tmdb.validateStatus(cleaned) }
            if (code in 200..299) {
                locator.settings.setTmdbApiKey(cleaned)
                _tmdbKeyState.value = TmdbUi.Valid
            } else {
                val reason = if (code <= 0) str.tmdbKeyNoNetwork else "HTTP $code"
                // Rendered inline in the field's card (green/red), not as a toast.
                _tmdbKeyState.value = TmdbUi.Invalid(str.tmdbKeyInvalid.format(reason))
            }
        }
    }

    fun resetTmdbState() {
        _tmdbKeyState.value = TmdbUi.Idle
    }

    fun setOutputTree(uri: String) {
        viewModelScope.launch {
            locator.settings.setOutputTreeUri(uri)
            toast(if (uri.isBlank()) str.outputAppStorage else str.outputFolderSet)
        }
    }


    fun setSubfolders(movies: String, series: String) {
        viewModelScope.launch {
            locator.settings.setMoviesSubfolder(movies)
            locator.settings.setSeriesSubfolder(series)
            toast(str.foldersSaved)
        }
    }

    fun setUnmeteredOnly(v: Boolean) {
        viewModelScope.launch { locator.settings.setUnmeteredOnly(v) }
    }

    /** Language is stored, and the root of the UI re-reads it through LocalStrings. */
    fun setLanguage(tag: String) {
        viewModelScope.launch {
            locator.settings.setLanguage(tag)
            toast(str.languageSwitched)
        }
    }

    fun setAutoRemove(v: Boolean) {
        viewModelScope.launch { locator.settings.setAutoRemoveCompleted(v) }
    }

    fun setMaxConcurrent(v: Int) {
        viewModelScope.launch {
            locator.settings.setMaxConcurrent(v)
            locator.manager.applyConcurrency(v)
        }
    }

    fun runKeystoreSelfTest() {
        val ok = com.ddownloader.android.data.CryptoBox.selfTest()
        toast(if (ok) str.keystoreOk else str.keystoreFailed)
    }

    fun clearLogs() = AppLog.clear()

    // ── Media layout preview (Settings diagnostics) ───────────────────────
    fun layoutPreview(name: String): String {
        val parsed = MediaLayout.parseReleaseName(name)
        val dest = MediaLayout.computeDestination(MediaLayout.Kind.SERIES, name, null, parsed.season)
        return "${parsed.title} • ${parsed.year ?: "?"} → ${dest.relativePath}"
    }
}
