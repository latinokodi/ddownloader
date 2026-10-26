package com.ddownloader.android.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.data.SettingsRepository
import com.ddownloader.android.data.db.DownloadRecord
import com.ddownloader.android.data.db.DownloadsDb
import com.ddownloader.android.data.db.TransferState
import com.ddownloader.android.storage.MediaLayout
import com.ddownloader.android.storage.Storage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Resolves (or re-resolves) a fresh direct URL for a debrid transfer. */
fun interface LinkResolver {
    suspend fun resolve(record: DownloadRecord): String
}

/** Resolves a canonical title/year so the Jellyfin layout matches the original. */
fun interface TitleResolver {
    suspend fun resolve(name: String, kind: MediaLayout.Kind): Pair<String, Int?>?
}

/**
 * Queue + lifecycle for transfers.
 *
 * Survives process death by design: the queue lives in SQLite and the resume
 * offset is the partial file's length, so a cold start can continue exactly
 * where it stopped ([resumeInterrupted]). Concurrency mirrors the Electron
 * worker's MAX_CONCURRENT_DOWNLOADS = 3.
 */
class DownloadManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val db: DownloadsDb,
) {
    data class Live(
        val id: String,
        val name: String,
        val state: TransferState,
        val bytes: Long,
        val total: Long,
        val percent: Int,
        val bytesPerSecond: Double,
        val eta: String,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // NOTE: Semaphore(permits). The second constructor argument is "initially
    // acquired", NOT the maximum — Semaphore(3, 3) leaves zero permits and
    // suspends every transfer forever. Found on device; kept as a regression note.
    private var permits = Semaphore(3)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val resumedOnce = AtomicBoolean(false)
    @Volatile private var lastRetryScheduledAt = 0L

    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())
    val live: StateFlow<Map<String, Live>> = _live.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    var linkResolver: LinkResolver? = null
    var titleResolver: TitleResolver? = null

    /** Called whenever the set of running transfers changes, so the service can start/stop. */
    var onActiveChanged: (() -> Unit)? = null

    fun applyConcurrency(max: Int) {
        permits = Semaphore(max.coerceIn(1, 5))
    }

    fun enqueue(record: DownloadRecord) {
        db.upsert(record)
        start(record.id)
    }

    fun start(id: String) {
        val rec = db.byId(id) ?: return
        if (rec.state == TransferState.COMPLETED) return
        if (jobs.containsKey(id)) return
        cancelFlags[id] = AtomicBoolean(false)
        db.markState(id, TransferState.QUEUED)
        jobs[id] = scope.launch {
            try {
                permits.withPermit { runOne(id) }
            } finally {
                jobs.remove(id)
                publishActive()
            }
        }
        publishActive()
    }

    fun pause(id: String) {
        cancelFlags[id]?.set(true)
        jobs[id]?.cancel()
        jobs.remove(id)
        db.markState(id, TransferState.PAUSED)
        updateLive(id) { it.copy(state = TransferState.PAUSED, bytesPerSecond = 0.0) }
        publishActive()
    }

    fun cancel(id: String) {
        cancelFlags[id]?.set(true)
        jobs[id]?.cancel()
        jobs.remove(id)
        val rec = db.byId(id)
        if (rec != null) {
            File(rec.partialPath).takeIf { it.exists() }?.delete()
            db.markState(id, TransferState.CANCELLED)
        }
        _live.value = _live.value - id
        publishActive()
    }

    /**
     * Re-arms transfers that stopped because the network disappeared. Only rows with
     * a resumable partial (bytes on disk, still short of the expected size) qualify,
     * so a permanently failing transfer is not retried on every connectivity blip.
     */
    fun retryNetworkFailures(): Int {
        val candidates = db.records.value.filter { r ->
            r.state == TransferState.FAILED &&
                r.bytesDone > 0 &&
                (r.expectedSize <= 0 || r.bytesDone < r.expectedSize)
        }
        if (candidates.isEmpty()) return 0
        AppLog.i(TAG, "connectivity restored — retrying ${candidates.size} interrupted transfer(s)")
        candidates.forEach { retry(it.id) }
        return candidates.size
    }

    fun retry(id: String) {
        db.markState(id, TransferState.QUEUED, error = null)
        start(id)
    }

    fun clearCompleted() {
        db.deleteByState(TransferState.COMPLETED, TransferState.CANCELLED)
    }

    fun activeIds(): List<String> = jobs.keys.toList()

    /** Restart everything that was mid-flight when the process died. */
    fun resumeInterrupted(): Int {
        // Guarded: the Activity, DownloadService and ResumeWorker all call this on
        // every start; only the first call per process may touch the queue.
        if (!resumedOnce.compareAndSet(false, true)) return 0
        val interrupted = db.interrupted()
        interrupted.forEach { rec ->
            db.markState(rec.id, TransferState.QUEUED)
            start(rec.id)
        }
        if (interrupted.isNotEmpty()) {
            AppLog.i(TAG, "resuming ${interrupted.size} interrupted transfer(s) after process death")
        }
        return interrupted.size
    }

    private suspend fun runOne(id: String) {
        val rec = db.byId(id) ?: return
        val cfg = settings.current()
        val partial = if (rec.partialPath.isNotBlank()) File(rec.partialPath)
        else Storage.partialFile(context, rec.id, rec.name)

        db.upsert(rec.copy(partialPath = partial.absolutePath))
        updateLive(id) {
            Live(id, rec.name, TransferState.RESOLVING, Storage.partialSize(partial), rec.expectedSize, 0, 0.0, "—")
        }
        db.markState(id, TransferState.RESOLVING)

        // Debrid transfers need a fresh direct link (they expire). A direct URL
        // already present is used as-is.
        var url = rec.url
        if (url.isBlank() || rec.service != "direct") {
            val resolver = linkResolver
            if (resolver != null) {
                url = try {
                    resolver.resolve(rec)
                } catch (t: Throwable) {
                    fail(id, "Link resolution failed: ${t.message}")
                    return
                }
            }
        }
        if (url.isBlank()) {
            fail(id, "No download URL could be resolved")
            return
        }

        val host = hostOf(url)
        val loopback = com.ddownloader.android.data.Net.isLoopback(host)
        val allowedHost: (String) -> Boolean = { h -> loopback || HostAllowlist.isAllowed(rec.service, h) }
        if (!loopback && !HostAllowlist.isAllowed(rec.service, host)) {
            fail(id, "Refusing to download from non-allowlisted host $host")
            return
        }

        db.markState(id, TransferState.DOWNLOADING)
        val engine = DownloadEngine()

        // Parity with worker.ts: up to 3 attempts per file, backoff attempt*2000 ms.
        // Each attempt resumes from the bytes already on disk, so a cut/truncated
        // stream continues instead of restarting. The desktop original additionally
        // re-requested a fresh signed CDN link per attempt; here the resolver is
        // re-run for debrid transfers between attempts if the failure looks auth-y.
        var attempt = 1
        var result: DownloadEngine.Result
        while (true) {
            if (cancelFlags[id]?.get() == true) {
                result = DownloadEngine.Result.Cancelled(Storage.partialSize(partial))
                break
            }
            if (attempt > 1 && rec.service != "direct") {
                val fresh = runCatching { linkResolver?.resolve(rec) }.getOrNull()
                if (!fresh.isNullOrBlank()) url = fresh
            }
            result = engine.transfer(
                req = DownloadEngine.Request2(
                    url = url,
                    partial = partial,
                    expectedSize = rec.expectedSize,
                    allowedHost = allowedHost,
                    etag = rec.etag,
                    lastModified = rec.lastModified,
                ),
                onProgress = { p ->
                    db.updateProgress(id, p.bytes, TransferState.DOWNLOADING)
                    updateLive(id) {
                        Live(
                            id = id, name = rec.name, state = TransferState.DOWNLOADING,
                            bytes = p.bytes, total = p.total, percent = p.percent,
                            bytesPerSecond = p.bytesPerSecond, eta = p.eta,
                        )
                    }
                },
                isCancelled = { cancelFlags[id]?.get() == true },
            )
            val failed = result as? DownloadEngine.Result.Failed
            if (failed != null && failed.rangeIgnored) {
                // The server ignored Range. electron/worker.ts "accepts what is on
                // disk" here, which silently publishes a TRUNCATED file as complete
                // (verified on device: 4,063,232 bytes marked COMPLETED). We refuse
                // to do that: if the bytes on disk are short of the expected size,
                // discard the partial and re-fetch from zero — the only way to get a
                // byte-correct file from a server that cannot resume.
                if (rec.expectedSize > 0 && failed.bytes < rec.expectedSize && attempt < MAX_ATTEMPTS) {
                    AppLog.w(
                        TAG,
                        "server ignored Range at ${failed.bytes}/${rec.expectedSize} bytes — restarting from zero (attempt $attempt)",
                    )
                    partial.delete()
                    attempt++
                    continue
                }
                break
            }
            val retryable = failed != null &&
                attempt < MAX_ATTEMPTS &&
                cancelFlags[id]?.get() != true
            if (!retryable) break
            AppLog.w(TAG, "attempt $attempt/$MAX_ATTEMPTS failed (${failed.message}) — retrying from ${failed.bytes} bytes")
            kotlinx.coroutines.delay(2000L * attempt)
            attempt++
        }

        when (result) {
            is DownloadEngine.Result.Complete -> finish(id, rec, partial, result.bytes, cfg)
            is DownloadEngine.Result.Cancelled -> {
                db.markState(id, TransferState.PAUSED)
                updateLive(id) { it.copy(state = TransferState.PAUSED, bytesPerSecond = 0.0) }
            }
            is DownloadEngine.Result.Failed -> {
                if (result.rangeIgnored && rec.expectedSize > 0 && result.bytes >= rec.expectedSize) {
                    // The partial already holds the complete file — nothing to fetch.
                    finish(id, rec, partial, result.bytes, cfg)
                } else if (result.rangeIgnored) {
                    fail(
                        id,
                        "Server ignored the resume request and the partial file is incomplete " +
                            "(${result.bytes}/${rec.expectedSize} bytes). Retry to download from scratch.",
                        result.bytes,
                    )
                } else {
                    fail(id, result.message, result.bytes)
                }
            }
        }
    }

    private suspend fun finish(
        id: String,
        rec: DownloadRecord,
        partial: File,
        bytes: Long,
        cfg: SettingsRepository.Settings,
        verifyExact: Boolean = true,
    ) {
        if (verifyExact && rec.expectedSize > 0 && bytes != rec.expectedSize) {
            fail(id, "Size mismatch: got $bytes of ${rec.expectedSize} bytes", bytes)
            return
        }
        val kind = when (rec.mediaType) {
            "movie" -> MediaLayout.Kind.MOVIE
            "series" -> MediaLayout.Kind.SERIES
            else -> null
        }
        val subfolder: String = if (kind == null) {
            "" // non-media keeps the flat root
        } else {
            val tmdb = try {
                titleResolver?.resolve(rec.name, kind)
            } catch (t: Throwable) {
                null
            }
            val dest = MediaLayout.computeDestination(kind, rec.name, tmdb, rec.season)
            val base = when (kind) {
                MediaLayout.Kind.MOVIE -> cfg.moviesSubfolder
                MediaLayout.Kind.SERIES -> cfg.seriesSubfolder
            }
            (listOf(base) + dest.segments).joinToString("/")
        }
        val fileName = if (kind == null) {
            MediaLayout.safePathPart(rec.name)
        } else {
            MediaLayout.safePathPart(rec.name.substringAfterLast('/').substringAfterLast('\\'))
        }

        try {
            val published = Storage.publish(
                context = context,
                partial = partial,
                treeUri = cfg.outputTreeUri.takeIf { it.isNotBlank() },
                subfolder = subfolder.ifBlank { "" },
                fileName = fileName,
            )
            db.upsert(
                rec.copy(
                    state = TransferState.COMPLETED,
                    bytesDone = published.size,
                    expectedSize = if (rec.expectedSize > 0) rec.expectedSize else published.size,
                    finalUri = published.uri,
                    destDisplay = published.displayPath,
                    partialPath = "",
                    error = null,
                )
            )
            updateLive(id) {
                Live(id, rec.name, TransferState.COMPLETED, published.size, published.size, 100, 0.0, "done")
            }
            AppLog.i(TAG, "completed ${rec.name} → ${published.displayPath}")
        } catch (t: Throwable) {
            fail(id, "Publish failed: ${t.javaClass.simpleName}: ${t.message}", bytes)
        }
    }

    private fun fail(id: String, message: String, bytes: Long = 0) {
        AppLog.e(TAG, "transfer $id failed: $message")
        db.markState(id, TransferState.FAILED, message)
        updateLive(id) { it.copy(state = TransferState.FAILED, error = message, bytes = bytes) }
        // A dead link/host error is permanent; a connectivity error is not. Schedule a
        // network-constrained retry so an outage is recovered without user action
        // (the lifecycle "network toggle" scenario is the regression test for this).
        // Throttled to one schedule per minute so a host that keeps refusing cannot
        // turn into a retry loop.
        val now = System.currentTimeMillis()
        if (CONNECTIVITY_ERROR.containsMatchIn(message) && now - lastRetryScheduledAt > RETRY_SCHEDULE_MIN_INTERVAL_MS) {
            lastRetryScheduledAt = now
            AppLog.i(TAG, "failure looks connectivity-related — scheduling a network-constrained retry")
            runCatching { ResumeWorker.scheduleRetrySoon(context) }
        }
    }

    private fun updateLive(id: String, block: (Live) -> Live) {
        val current = _live.value[id] ?: Live(id, "", TransferState.QUEUED, 0, 0, 0, 0.0, "—")
        _live.value = _live.value + (id to block(current))
    }

    private fun publishActive() {
        _activeCount.value = jobs.size
        onActiveChanged?.invoke()
    }

    /** Host component of [url], or "" when it is not a parsable absolute URL. */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    companion object {
        private const val TAG = "DownloadMgr"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_SCHEDULE_MIN_INTERVAL_MS = 60_000L
        private val CONNECTIVITY_ERROR = Regex(
            "ConnectException|SocketException|SocketTimeout|UnknownHost|unreachable|EAI_AGAIN|connection abort|Software caused connection abort|Failed to connect",
            RegexOption.IGNORE_CASE,
        )
    }
}