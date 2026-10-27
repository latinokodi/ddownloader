package com.ddownloader.android.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ddownloader.android.core.AppLog
import java.io.File
import java.io.IOException

/**
 * Scoped-storage output.
 *
 * Partial files always live in app-private storage (`filesDir/partials`) because
 * that is the only place we can seek/append reliably and keep across process
 * death without any permission. When a transfer completes it is *published*:
 *
 *  • no SAF tree chosen  → the phone's public **Download** folder, under `DDownloader/`
 *                          (Android 10+: MediaStore, no permission needed;
 *                           Android 8-9: direct file write with WRITE_EXTERNAL_STORAGE,
 *                           falling back to app storage if that permission is denied)
 *  • SAF tree chosen     → `DocumentFile` inside the user-picked folder
 *
 * `WRITE_EXTERNAL_STORAGE` is declared with `maxSdkVersion="28"` and only requested on
 * Android 8-9, where the public Download folder cannot be written through MediaStore.
 */
object Storage {

    private const val TAG = "Storage"

    fun partialDir(context: Context): File =
        File(context.filesDir, "partials").apply { mkdirs() }

    fun partialFile(context: Context, id: String, name: String): File {
        val ext = name.substringAfterLast('.', "")
        val safe = MediaLayout.safePathPart(name.substringBeforeLast('.', name)).take(80)
        val fileName = buildString {
            append(id.take(8))
            append('-')
            append(safe)
            if (ext.isNotBlank()) { append('.'); append(ext.take(8)) }
            append(".part")
        }
        return File(partialDir(context), fileName)
    }

    fun defaultOutputRoot(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Download").apply { mkdirs() }

    data class PublishResult(val displayPath: String, val uri: String, val size: Long)

    /**
     * Move [partial] into its final destination. Uses a streaming copy so it works
     * for both a plain File target and a SAF document; the partial is deleted only
     * after the copy is fully flushed.
     */
    suspend fun publish(
        context: Context,
        partial: File,
        treeUri: String?,
        subfolder: String,
        fileName: String,
    ): PublishResult = withContext(Dispatchers.IO) {
        require(partial.exists()) { "partial missing: ${partial.absolutePath}" }
        val safeName = MediaLayout.safePathPart(fileName).ifBlank { "download.bin" }
        // Non-media transfers have no subfolder: resolve("") must stay at the root,
        // and a blank segment must never be sanitised into a literal "Unknown" dir.
        val safeSub = if (subfolder.isBlank()) {
            ""
        } else {
            subfolder.split('/').filter { it.isNotBlank() }
                .joinToString("/") { MediaLayout.safePathPart(it) }
        }

        if (treeUri.isNullOrBlank()) {
            // Public Download folder: the one the user already knows, not an app-scoped copy.
            val relative = buildString {
                append(Environment.DIRECTORY_DOWNLOADS)
                append("/DDownloader")
                if (safeSub.isNotBlank()) { append('/'); append(safeSub) }
                append('/')
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return@withContext publishToMediaStore(context, partial, relative, safeName)
            }
            // Android 8-9: scoped storage does not apply, write the file directly. If the legacy
            // permission was denied, keep working by falling back to app storage.
            val publicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = publicRoot.resolve("DDownloader").resolve(safeSub).apply { mkdirs() }
            if (dir.canWrite()) {
                val out = uniqueFile(dir, safeName)
                partial.copyTo(out, overwrite = true)
                val size = out.length()
                partial.delete()
                AppLog.i(TAG, "published to ${out.absolutePath} ($size bytes)")
                return@withContext PublishResult(out.absolutePath, Uri.fromFile(out).toString(), size)
            }
            AppLog.w(TAG, "public Download not writable — falling back to app storage")
            val dir2 = defaultOutputRoot(context).resolve(safeSub).apply { mkdirs() }
            val out = uniqueFile(dir2, safeName)
            partial.copyTo(out, overwrite = true)
            val size = out.length()
            partial.delete()
            return@withContext PublishResult(out.absolutePath, Uri.fromFile(out).toString(), size)
        }

        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw IOException("SAF tree not accessible (permission revoked?)")
        var dir = tree
        for (seg in safeSub.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(seg) ?: dir.createDirectory(seg)
                ?: throw IOException("Cannot create directory $seg in SAF tree")
        }
        val existing = dir.findFile(safeName)
        val target = if (existing != null && existing.exists()) {
            val base = safeName.substringBeforeLast('.', safeName)
            val ext = safeName.substringAfterLast('.', "")
            val alt = if (ext.isBlank()) "$base-${System.currentTimeMillis()}" else "$base-${System.currentTimeMillis()}.$ext"
            dir.createFile("application/octet-stream", alt)
        } else {
            dir.createFile("application/octet-stream", safeName)
        } ?: throw IOException("Cannot create file in SAF tree")

        context.contentResolver.openOutputStream(target.uri, "w")?.use { os ->
            partial.inputStream().use { ins -> ins.copyTo(os, 256 * 1024) }
            os.flush()
        } ?: throw IOException("Cannot open SAF output stream")

        val size = target.length()
        partial.delete()
        AppLog.i(TAG, "published to ${target.uri} ($size bytes)")
        PublishResult("${tree.name ?: "tree"}/$safeSub/$safeName", target.uri.toString(), size)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            val candidate = if (ext.isBlank()) "$base-$i" else "$base-$i.$ext"
            f = File(dir, candidate)
            i++
        }
        return f
    }

    /** Bytes actually on disk for a resume — the source of truth for the offset. */
    fun partialSize(partial: File): Long = if (partial.exists()) partial.length() else 0L

    fun wipePartials(context: Context) {
        partialDir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Deletes .part files that no queue record refers to (e.g. after `pm clear` of
     * the database, or a row cancelled while the app was dead). Partial names start
     * with the first 8 characters of the owning record id.
     */
    fun pruneOrphans(context: Context, referencedIds: Collection<String>): Int {
        val prefixes = referencedIds.map { it.take(8) }.toSet()
        var removed = 0
        partialDir(context).listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".part") && f.name.take(8) !in prefixes) {
                if (f.delete()) removed++
            }
        }
        if (removed > 0) AppLog.i(TAG, "pruned $removed orphaned partial file(s)")
        return removed
    }

    /**
     * Writes [partial] into `Download/DDownloader/<sub>` through MediaStore. No permission is
     * required on Android 10+, and IS_PENDING keeps the file hidden from other apps until the copy
     * is complete, so a half-written video never shows up in the gallery or Files.
     */
    private fun publishToMediaStore(
        context: Context,
        partial: File,
        relativePath: String,
        fileName: String,
    ): PublishResult {
        val resolver = context.contentResolver
        val ext = fileName.substringAfterLast('.', "")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the Download entry")
        try {
            resolver.openOutputStream(uri)?.use { os ->
                partial.inputStream().use { ins -> ins.copyTo(os, 256 * 1024) }
                os.flush()
            } ?: throw IOException("Cannot open MediaStore output stream")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        val size = partial.length()
        partial.delete()
        val display = relativePath + fileName
        AppLog.i(TAG, "published to $display ($size bytes)")
        return PublishResult(display, uri.toString(), size)
    }
}
