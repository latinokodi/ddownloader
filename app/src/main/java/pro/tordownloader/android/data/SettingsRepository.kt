package pro.tordownloader.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Application settings, ported from the Electron `Settings` table (electron/db.ts).
 *
 * Secret material (debrid tokens, TMDB key) is encrypted with [CryptoBox] before
 * it is written, so the DataStore file only ever holds keystore-bound ciphertext.
 */
class SettingsRepository(private val context: Context) {

    data class Settings(
        // Secrets — decrypted in memory only.
        val torboxToken: String = "",
        val realdebridToken: String = "",
        val realdebridRefreshToken: String = "",
        val realdebridClientId: String = "",
        val realdebridClientSecret: String = "",
        val tmdbApiKey: String = "",
        // Destination: a persisted SAF tree URI (scoped storage). Empty → app-private dir.
        val outputTreeUri: String = "",
        val moviesSubfolder: String = "Movies",
        val seriesSubfolder: String = "Series",
        val autoRemoveCompleted: Boolean = false,
        val language: String = "en",
        val maxConcurrentDownloads: Int = 3,
        val unmeteredOnly: Boolean = false,
    ) {
        val hasTorBox: Boolean get() = torboxToken.isNotBlank()
        val hasRealDebrid: Boolean get() = realdebridToken.isNotBlank()
        val hasAnyDebrid: Boolean get() = hasTorBox || hasRealDebrid
        val hasTmdb: Boolean get() = tmdbApiKey.isNotBlank()
    }

    private object K {
        val torbox = stringPreferencesKey("torbox_token_enc")
        val rdToken = stringPreferencesKey("rd_token_enc")
        val rdRefresh = stringPreferencesKey("rd_refresh_enc")
        val rdClientId = stringPreferencesKey("rd_client_id_enc")
        val rdClientSecret = stringPreferencesKey("rd_client_secret_enc")
        val tmdb = stringPreferencesKey("tmdb_key_enc")
        val treeUri = stringPreferencesKey("output_tree_uri")
        val moviesDir = stringPreferencesKey("movies_subfolder")
        val seriesDir = stringPreferencesKey("series_subfolder")
        val autoRemove = booleanPreferencesKey("auto_remove_completed")
        val language = stringPreferencesKey("language")
        val maxConcurrent = intPreferencesKey("max_concurrent")
        val unmeteredOnly = booleanPreferencesKey("unmetered_only")
    }

    val settings: StateFlow<Settings> = context.dataStore.data
        .map { p ->
            Settings(
                torboxToken = CryptoBox.decrypt(p[K.torbox]).orEmpty(),
                realdebridToken = CryptoBox.decrypt(p[K.rdToken]).orEmpty(),
                realdebridRefreshToken = CryptoBox.decrypt(p[K.rdRefresh]).orEmpty(),
                realdebridClientId = CryptoBox.decrypt(p[K.rdClientId]).orEmpty(),
                realdebridClientSecret = CryptoBox.decrypt(p[K.rdClientSecret]).orEmpty(),
                tmdbApiKey = CryptoBox.decrypt(p[K.tmdb]).orEmpty(),
                outputTreeUri = p[K.treeUri].orEmpty(),
                moviesSubfolder = p[K.moviesDir] ?: "Movies",
                seriesSubfolder = p[K.seriesDir] ?: "Series",
                autoRemoveCompleted = p[K.autoRemove] ?: false,
                language = p[K.language] ?: "en",
                maxConcurrentDownloads = (p[K.maxConcurrent] ?: 3).coerceIn(1, 5),
                unmeteredOnly = p[K.unmeteredOnly] ?: false,
            )
        }
        .stateIn(
            scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
            started = SharingStarted.Eagerly,
            initialValue = Settings(),
        )

    suspend fun current(): Settings = settings.first()

    suspend fun setTorboxToken(token: String) = put { it[K.torbox] = CryptoBox.encrypt(token.trim()) }
    suspend fun setRealDebridToken(token: String) = put { it[K.rdToken] = CryptoBox.encrypt(token.trim()) }
    suspend fun setRealDebridCredentials(clientId: String, clientSecret: String, refresh: String) = put {
        it[K.rdClientId] = CryptoBox.encrypt(clientId)
        it[K.rdClientSecret] = CryptoBox.encrypt(clientSecret)
        it[K.rdRefresh] = CryptoBox.encrypt(refresh)
    }
    suspend fun setTmdbApiKey(key: String) = put { it[K.tmdb] = CryptoBox.encrypt(key.trim()) }
    suspend fun setOutputTreeUri(uri: String) = put { it[K.treeUri] = uri }
    suspend fun setMoviesSubfolder(v: String) = put { it[K.moviesDir] = v.ifBlank { "Movies" } }
    suspend fun setSeriesSubfolder(v: String) = put { it[K.seriesDir] = v.ifBlank { "Series" } }
    suspend fun setAutoRemoveCompleted(v: Boolean) = put { it[K.autoRemove] = v }
    suspend fun setLanguage(v: String) = put { it[K.language] = v }
    suspend fun setMaxConcurrent(v: Int) = put { it[K.maxConcurrent] = v.coerceIn(1, 5) }
    suspend fun setUnmeteredOnly(v: Boolean) = put { it[K.unmeteredOnly] = v }

    suspend fun clearAllSecrets() = put {
        it.remove(K.torbox); it.remove(K.rdToken); it.remove(K.rdRefresh)
        it.remove(K.rdClientId); it.remove(K.rdClientSecret); it.remove(K.tmdb)
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    /** Fire-and-forget variant used from UI callbacks. */
    fun launchSet(block: suspend SettingsRepository.() -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { block() }
    }
}
