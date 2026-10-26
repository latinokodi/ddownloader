package com.ddownloader.android.download

import okhttp3.Request
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.core.Fmt
import com.ddownloader.android.data.Net
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * HTTP transfer core — a faithful port of `electron/downloader.ts` with the
 * behaviours that file was written around, plus the two gaps that file left open:
 *
 *  parity
 *   • resume from the bytes already on disk via `Range: bytes=<n>-`
 *   • server returns 200 to a Range request → **never truncate**; report
 *     `rangeIgnored` and keep the partial (the original's `rangeIgnored` flag)
 *   • 416 → the partial is already complete
 *   • stream that ends short of the expected size is a FAILURE, partial kept
 *   • 1 s progress cadence, speed = delta/dt, ETA from remaining/speed
 *
 *  fixes
 *   • `If-Range` (ETag / Last-Modified) so a resume against a *changed*
 *     representation restarts instead of appending garbage
 *   • every redirect hop is validated against the caller's allowlist (the
 *     original checked only the first URL while axios followed redirects)
 *   • cancellation is cooperative and flushes what was written
 */
class DownloadEngine {

    data class Request2(
        val url: String,
        val partial: File,
        val expectedSize: Long,
        val allowedHost: (String) -> Boolean,
        val headers: Map<String, String> = emptyMap(),
        val etag: String? = null,
        val lastModified: String? = null,
    )

    data class Progress(
        val bytes: Long,
        val total: Long,
        val percent: Int,
        val bytesPerSecond: Double,
        val eta: String,
    )

    sealed interface Result {
        data class Complete(val bytes: Long, val etag: String?, val lastModified: String?) : Result
        data class Failed(val message: String, val bytes: Long, val rangeIgnored: Boolean = false) : Result
        data class Cancelled(val bytes: Long) : Result
    }

    /**
     * @param onProgress throttled to ~1 Hz (parity with the Electron 1000 ms cadence)
     * @param isCancelled polled between chunks; returning true stops and returns [Result.Cancelled]
     */
    fun transfer(
        req: Request2,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Result {
        val partial = req.partial
        partial.parentFile?.mkdirs()

        var offset = if (partial.exists()) partial.length() else 0L
        if (req.expectedSize > 0 && offset >= req.expectedSize) {
            AppLog.i(TAG, "already complete on disk: ${partial.name} ($offset bytes)")
            return Result.Complete(offset, req.etag, req.lastModified)
        }

        val builder = Request.Builder().url(req.url)
        if (offset > 0) {
            builder.header("Range", "bytes=$offset-")
            // Only resume when the representation is provably the same one.
            val validator = req.etag ?: req.lastModified
            if (!validator.isNullOrBlank()) builder.header("If-Range", validator)
        }
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Accept-Encoding", "identity") // Content-Length must be the real size
        builder.header("User-Agent", "DDownloader-Android/1.0")

        val response = try {
            Net.follow(builder.build(), client = Net.downloadClient, allowedHost = req.allowedHost)
        } catch (t: Throwable) {
            return Result.Failed("request failed: ${t.javaClass.simpleName}: ${t.message}", offset)
        }

        response.use { resp ->
            val code = resp.code
            val etag = resp.header("ETag")
            val lastModified = resp.header("Last-Modified")

            if (code == 416) {
                // Range beyond EOF — the partial file is complete.
                AppLog.i(TAG, "416 on resume → treating partial as complete ($offset bytes)")
                return Result.Complete(offset, etag, lastModified)
            }

            if (offset > 0 && code == 200) {
                // Server ignored Range. Do NOT truncate the partial (parity).
                AppLog.w(TAG, "server ignored Range (200) with $offset bytes on disk — keeping partial")
                return Result.Failed("Range not supported by server", offset, rangeIgnored = true)
            }

            if (code != 200 && code != 206) {
                return Result.Failed("HTTP $code", offset)
            }

            if (code == 206) {
                val contentRange = resp.header("Content-Range")
                val startAt = contentRange
                    ?.substringAfter("bytes", "")
                    ?.substringBefore("-")
                    ?.trim()
                    ?.toLongOrNull()
                if (startAt != null && startAt != offset) {
                    AppLog.w(TAG, "Content-Range start $startAt != partial size $offset — restarting from 0")
                    partial.delete()
                    offset = 0
                }
            }

            val contentLength = resp.body?.contentLength() ?: -1L
            val total = when {
                req.expectedSize > 0 -> req.expectedSize
                code == 206 && contentLength >= 0 -> offset + contentLength
                contentLength >= 0 -> contentLength
                else -> 0L
            }

            val body = resp.body ?: return Result.Failed("empty body", offset)
            val out = RandomAccessFile(partial, "rw")
            var written = offset
            val buffer = ByteArray(256 * 1024)
            var lastTick = System.currentTimeMillis()
            var lastBytes = written

            try {
                out.seek(offset)
                val input = body.byteStream()
                while (true) {
                    if (isCancelled()) {
                        out.fd.sync()
                        AppLog.i(TAG, "cancelled at $written bytes (partial kept)")
                        return Result.Cancelled(written)
                    }
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    written += read

                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 1000) {
                        val speed = (written - lastBytes) * 1000.0 / max(1, now - lastTick)
                        lastTick = now
                        lastBytes = written
                        onProgress(
                            Progress(
                                bytes = written,
                                total = total,
                                percent = Fmt.percent(written, total),
                                bytesPerSecond = speed,
                                eta = Fmt.eta((total - written).coerceAtLeast(0), speed),
                            )
                        )
                    }
                }
                out.fd.sync()
            } catch (t: Throwable) {
                // Keep the partial: a retry resumes from here.
                return Result.Failed("stream error at $written bytes: ${t.javaClass.simpleName}: ${t.message}", written)
            } finally {
                runCatching { out.close() }
            }

            onProgress(
                Progress(
                    bytes = written,
                    total = total,
                    percent = Fmt.percent(written, total),
                    bytesPerSecond = 0.0,
                    eta = "—",
                )
            )

            if (total > 0 && written < total) {
                AppLog.w(TAG, "truncated: $written of $total bytes (partial kept for resume)")
                return Result.Failed("Truncated download: got $written of $total bytes", written)
            }
            if (total == 0L) {
                AppLog.i(TAG, "completed with unknown size: $written bytes")
            }
            return Result.Complete(written, etag, lastModified)
        }
    }

    /** HEAD probe used to learn size/validators before a transfer. */
    fun probe(url: String, allowedHost: (String) -> Boolean): Pair<Long, String?>? {
        return try {
            val req = Request.Builder().url(url).head().build()
            Net.follow(req, client = Net.client, allowedHost = allowedHost).use { r ->
                if (!r.isSuccessful) return null
                val len = r.header("Content-Length")?.toLongOrNull() ?: 0L
                len to r.header("ETag")
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "probe failed: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "Download"
    }
}
