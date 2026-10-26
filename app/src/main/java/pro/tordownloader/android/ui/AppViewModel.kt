package pro.tordownloader.android.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pro.tordownloader.android.api.CometApi
import pro.tordownloader.android.api.TmdbApi
import pro.tordownloader.android.core.AppLog
import pro.tordownloader.android.data.ServiceLocator
import pro.tordownloader.android.data.db.DownloadRecord
import pro.tordownloader.android.data.db.TransferState
import pro.tordownloader.android.download.DownloadManager
import pro.tordownloader.android.download.DownloadService
import pro.tordownloader.android.download.ResumeWorker
import pro.tordownloader.android.storage.MediaLayout

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val locator = ServiceLocator

    val settings = locator.settings.settings
    val records: StateFlow<List<DownloadRecord>> = locator.db.records
    val live: StateFlow<Map<String, DownloadManager.Live>> = locator.manager.live
    val activeCount: StateFlow<Int> = locator.manager.activeCount
    val logs = AppLog.entries
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

    fun toast(text: String) {
        message = text
    }

    fun consumeMessage() {
        message = null
    }

    // ── Session ───────────────────────────────────────────────────────────
    /** Called when the UI becomes visible: re-arm the durable queue. */
    fun onUiVisible() {
        ResumeWorker.schedule(getApplication())
        viewModelScope.launch {
            val resumed = locator.manager.resumeInterrupted()
            if (resumed > 0) {
                DownloadService.start(getApplication())
                toast("Resuming $resumed interrupted download(s)")
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
                searchError = t.message ?: "Search failed"
            } finally {
                searching = false
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
                discoverError = t.message ?: "Discover failed"
            } finally {
                discoverLoading = false
            }
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
                val result = locator.comet.streams(type, imdb, if (type == "series") 1 else null, if (type == "series") 1 else null)
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
        val magnet = stream.magnet()
        if (magnet == null) {
            toast("Stream has no infoHash — cannot convert to a magnet")
            return
        }
        if (!settings.value.hasAnyDebrid) {
            toast("Link a TorBox or Real-Debrid account to download torrents")
            return
        }
        toast("Magnet resolved — add it through your debrid account")
        addMagnet(magnet, estimatedSize = stream.videoSize ?: 0L, name = stream.displayName())
        closeStreamSheet()
    }

    // ── Queue actions ─────────────────────────────────────────────────────
    fun addUrl(url: String, name: String? = null, expectedSize: Long = 0L) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        // Input validation: only http(s), a real host, and no embedded credentials
        // (userinfo in a URL would be silently sent to the file host).
        val parsed = runCatching { java.net.URI(clean) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            toast("Only http(s) links can be downloaded")
            return
        }
        if (parsed?.userInfo != null || parsed.host.isNullOrBlank()) {
            toast("That link is not a valid download URL")
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
        toast("Queued ${record.name}")
    }

    fun addMagnet(magnet: String, estimatedSize: Long = 0L, name: String = "magnet") {
        if (!settings.value.hasAnyDebrid) {
            toast("No debrid account linked")
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                if (settings.value.hasTorBox) {
                    val id = locator.torbox.addMagnet(magnet).getOrThrow()
                    val torrents = locator.torbox.listTorrents().getOrNull().orEmpty()
                    val torrent = torrents.firstOrNull { it.id == id }
                    val file = torrent?.files?.maxByOrNull { it.size }
                    if (torrent == null || file == null) {
                        toast("TorBox accepted the magnet (id $id); file list not ready yet")
                        return@launch
                    }
                    locator.manager.enqueue(
                        DownloadRecord(
                            url = "",
                            name = file.name.substringAfterLast('/'),
                            expectedSize = file.size,
                            service = "torbox",
                            remoteId = torrent.id,
                            fileId = file.id,
                            state = TransferState.QUEUED,
                        )
                    )
                    DownloadService.start(getApplication())
                    toast("Queued from TorBox")
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
                    toast("Queued from Real-Debrid")
                }
            } catch (t: Throwable) {
                toast("Could not add magnet: ${t.message}")
            } finally {
                busy = false
            }
        }
    }

    fun pause(id: String) = locator.manager.pause(id)
    fun resume(id: String) = locator.manager.start(id)
    fun cancel(id: String) = locator.manager.cancel(id)
    fun retry(id: String) = locator.manager.retry(id)

    fun clearCompleted() {
        locator.manager.clearCompleted()
        toast("Cleared finished transfers")
    }

    // ── Settings ──────────────────────────────────────────────────────────
    fun saveTorboxToken(token: String) {
        viewModelScope.launch {
            locator.settings.setTorboxToken(token)
            toast(if (token.isBlank()) "TorBox token cleared" else "TorBox token saved (encrypted)")
        }
    }

    fun saveRealDebridToken(token: String) {
        viewModelScope.launch {
            locator.settings.setRealDebridToken(token)
            toast(if (token.isBlank()) "Real-Debrid token cleared" else "Real-Debrid token saved (encrypted)")
        }
    }

    fun saveTmdbKey(key: String) {
        viewModelScope.launch {
            if (key.isBlank()) {
                locator.settings.setTmdbApiKey("")
                toast("TMDB key cleared")
                return@launch
            }
            if (locator.tmdb.validate(key)) {
                locator.settings.setTmdbApiKey(key)
                toast("TMDB key validated and saved")
            } else {
                toast("TMDB rejected that key")
            }
        }
    }

    fun setOutputTree(uri: String) {
        viewModelScope.launch {
            locator.settings.setOutputTreeUri(uri)
            toast(if (uri.isBlank()) "Using app storage" else "Output folder set")
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
        val ok = pro.tordownloader.android.data.CryptoBox.selfTest()
        toast(if (ok) "Keystore round-trip OK" else "Keystore self-test FAILED")
    }

    fun clearLogs() = AppLog.clear()

    // ── Media layout preview (Settings diagnostics) ───────────────────────
    fun layoutPreview(name: String): String {
        val parsed = MediaLayout.parseReleaseName(name)
        val dest = MediaLayout.computeDestination(MediaLayout.Kind.SERIES, name, null, parsed.season)
        return "${parsed.title} • ${parsed.year ?: "?"} → ${dest.relativePath}"
    }
}
