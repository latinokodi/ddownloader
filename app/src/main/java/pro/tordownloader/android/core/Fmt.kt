package pro.tordownloader.android.core

import java.util.Locale
import kotlin.math.abs

/** Byte/speed/ETA formatting. Mirrors the Electron worker's formatETA/bytes helpers. */
object Fmt {

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return if (i == 0) "$value B" else String.format(Locale.US, "%.2f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond <= 0) "—" else "${bytes(bytesPerSecond.toLong())}/s"

    /** Electron worker.ts formatETA: "<60s", "Xm Ys", "Xh Ym". */
    fun eta(remainingBytes: Long, bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return "—"
        val seconds = (remainingBytes / bytesPerSecond).toLong()
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100)

    /**
     * Relative age for the log/queue headers.
     *
     * Deliberately uses plain millisecond arithmetic: `java.time.Duration.toSeconds()`
     * is only available from API 31 on Android (lint NewApi error), and this app's
     * minSdk is 26 — no core-library desugaring is configured.
     */
    fun relative(millisAgo: Long): String {
        val totalSeconds = abs(millisAgo) / 1000
        val minutes = totalSeconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            totalSeconds < 60 -> "${totalSeconds}s ago"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            else -> "${days}d ago"
        }
    }
}
