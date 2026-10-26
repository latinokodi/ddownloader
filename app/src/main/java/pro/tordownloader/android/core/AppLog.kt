package pro.tordownloader.android.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * Process-wide, bounded log buffer surfaced in the Log screen.
 *
 * Ported from the Electron app's `logBuffer` (electron/main.ts): a 500-entry ring
 * buffer mirrored to the UI. Unlike the original — which logged whole URLs and
 * HTTP errors that could contain tokens — every line is passed through [redact]
 * first, so debrid API keys / magnets / signed CDN URLs never reach the buffer.
 */
object AppLog {

    enum class Level { INFO, WARN, ERROR, DEBUG }

    data class Entry(
        val id: Long,
        val timestamp: Instant,
        val level: Level,
        val tag: String,
        val text: String,
    ) {
        fun time(): String = TIME_FMT.format(timestamp)
    }

    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<Entry>()
    private var nextId = 1L

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    private val TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    @Synchronized
    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val safeText = redact(if (throwable == null) message else "$message — ${throwable.javaClass.simpleName}: ${throwable.message}")
        val entry = Entry(nextId++, Instant.now(), level, tag, safeText)
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
        if (level == Level.ERROR) _errorCount.value += 1

        val line = "${entry.time()} ${level.name.first()}/$tag: $safeText"
        when (level) {
            Level.ERROR -> Log.e(tag, line)
            Level.WARN -> Log.w(tag, line)
            Level.DEBUG -> Log.d(tag, line)
            Level.INFO -> Log.i(tag, line)
        }
    }

    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
        _errorCount.value = 0
    }

    /** Snapshot for export (EVIDENCE / share sheet). */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n") {
        "${it.timestamp} ${it.level.name} ${it.tag}: ${it.text}"
    }

    // ── Redaction ────────────────────────────────────────────────────────────
    // Anything that looks like a credential is masked before it is buffered.
    private val SECRET_QUERY = Regex(
        "(?i)(token|api_key|apikey|access_token|refresh_token|client_secret|password|code|auth)=([^&\\s\"']+)|" +
            "#([^&\\s\"']+)|" +
            "magnet:\\?[^\\s\"']*"
    )
    private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val LONG_HEX = Regex("\\b[a-fA-F0-9]{32,}\\b")

    fun redact(input: String): String {
        var out = SECRET_QUERY.replace(input) { m ->
            val key = m.groups[1]?.value
            if (key != null) "$key=***" else "***"
        }
        out = BEARER.replace(out, "Bearer ***")
        out = LONG_HEX.replace(out) { m ->
            val v = m.value
            if (v.length >= 32) "${v.take(6)}…${v.takeLast(4)}" else v
        }
        return out
    }
}
