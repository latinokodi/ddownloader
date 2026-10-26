package com.ddownloader.android.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.ddownloader.android.BuildConfig
import com.ddownloader.android.core.AppLog

/**
 * Settings backup / restore (skill: backup-recovery).
 *
 * An in-place APK update already preserves app data, but a reinstall (new device,
 * uninstalled app) loses it. This exports the non-secret configuration to a
 * user-chosen file through SAF (no storage permission) so it can be restored.
 *
 * Deliberately excludes secrets (debrid tokens, TMDB key, Keystore ciphertext): a
 * backup that gets shared or synced must not carry account credentials. Only presence
 * flags are recorded, so the UI can ask the user to re-enter tokens after a restore.
 *
 * File IO runs on Dispatchers.IO - doing it on the caller scope threw
 * NetworkOnMainThreadException in the update checker, so the hop is enforced here too.
 */
class SettingsBackup(private val context: Context, private val settings: SettingsRepository) {

    private val json = Json { prettyPrint = true }

    data class ImportResult(val applied: List<String>, val ignoredSecrets: Boolean)

    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        val root = buildJsonObject {
            put("format", 1)
            put("applicationId", context.packageName)
            put("versionName", BuildConfig.VERSION_NAME)
            put("exportedAt", System.currentTimeMillis())
            put("moviesSubfolder", s.moviesSubfolder)
            put("seriesSubfolder", s.seriesSubfolder)
            put("autoRemoveCompleted", s.autoRemoveCompleted)
            put("language", s.language)
            put("maxConcurrentDownloads", s.maxConcurrentDownloads)
            put("unmeteredOnly", s.unmeteredOnly)
            put("outputTreeUri", s.outputTreeUri)
            put("hasTorboxToken", s.hasTorBox)
            put("hasRealDebridToken", s.hasRealDebrid)
            put("hasTmdbKey", s.hasTmdb)
        }
        Json.encodeToString(JsonObject.serializer(), root)
    }

    suspend fun exportTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = exportText()
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("could not open the destination file")
            AppLog.i(TAG, "settings exported (secrets excluded)")
            text.length
        }.onFailure { AppLog.e(TAG, "export failed: " + it.message, it) }
    }

    suspend fun importFrom(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("could not read the backup file")
            val root = json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.content == "1") { "unsupported backup format" }
            val applied = mutableListOf<String>()

            fun str(key: String): String? = (root[key] as? JsonPrimitive)?.content
            fun bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
            fun int(key: String): Int? = str(key)?.toIntOrNull()

            str("moviesSubfolder")?.let { settings.setMoviesSubfolder(it); applied += "movies folder" }
            str("seriesSubfolder")?.let { settings.setSeriesSubfolder(it); applied += "series folder" }
            bool("autoRemoveCompleted")?.let { settings.setAutoRemoveCompleted(it); applied += "auto-remove" }
            str("language")?.let { settings.setLanguage(it); applied += "language" }
            int("maxConcurrentDownloads")?.let { settings.setMaxConcurrent(it); applied += "concurrency" }
            bool("unmeteredOnly")?.let { settings.setUnmeteredOnly(it); applied += "unmetered only" }
            str("outputTreeUri")?.takeIf { it.isNotBlank() }?.let { uriString ->
                val reachable = runCatching {
                    context.contentResolver.openOutputStream(Uri.parse(uriString), "w")?.use { }
                    true
                }.getOrDefault(false)
                if (reachable) {
                    settings.setOutputTreeUri(uriString)
                    applied += "download folder"
                } else {
                    AppLog.w(TAG, "saved folder not reachable on this device - kept current setting")
                }
            }
            val ignoredSecrets = listOf("hasTorboxToken", "hasRealDebridToken", "hasTmdbKey")
                .any { str(it) == "true" }
            AppLog.i(TAG, "settings imported: " + applied.joinToString())
            ImportResult(applied, ignoredSecrets)
        }.onFailure { AppLog.e(TAG, "import failed: " + it.message, it) }
    }

    companion object {
        private const val TAG = "Backup"
    }
}
