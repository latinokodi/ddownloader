package pro.tordownloader.android.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.tordownloader.android.core.AppLog
import java.io.File
import java.io.IOException

/**
 * Scoped-storage output.
 *
 * Partial files always live in app-private storage (`filesDir/partials`) because
 * that is the only place we can seek/append reliably and keep across process
 * death without any permission. When a transfer completes it is *published*:
 *
 *  • no SAF tree chosen  → `<external-files>/Download/<subfolder>/<name>`
 *                          (app-scoped, zero permissions, visible to the user
 *                          through the Files app and to `adb pull`)
 *  • SAF tree chosen     → `DocumentFile` inside the user-picked folder
 *
 * No `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` is ever requested.
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
            val dir = defaultOutputRoot(context).resolve(safeSub).apply { mkdirs() }
            val out = uniqueFile(dir, safeName)
            partial.copyTo(out, overwrite = true)
            val size = out.length()
            partial.delete()
            AppLog.i(TAG, "published to ${out.absolutePath} ($size bytes)")
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
}
