package com.ddownloader.android.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.ddownloader.android.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ddownloader.android.data.SettingsRepository

/**
 * TMDB (The Movie Database) client — port of `electron/tmdb-provider.py`'s
 * search path, used for (a) Discover, and (b) canonical title/year so the
 * Jellyfin folder layout matches the Electron app's `tmdbResolve`.
 */
class TmdbApi(private val settings: SettingsRepository) {

    data class Item(
        val id: Int,
        val title: String,
        val year: Int?,
        val overview: String,
        val posterPath: String?,
        val imdbId: String?,
        val mediaType: String,
        val voteAverage: Double,
    )

    private suspend fun key(): String = settings.current().tmdbApiKey

    private suspend fun get(path: String, extra: String = ""): JsonObject? {
        val k = key()
        if (k.isBlank()) return null
        return runCatching {
            val sep = if (path.contains('?')) "&" else "?"
            val res = Web.get("https://api.themoviedb.org/3$path${sep}api_key=$k&language=es-ES$extra")
            if (res.code !in 200..299) error("HTTP ${res.code}")
            Web.json.parseToJsonElement(res.body).jsonObject
        }.onFailure { AppLog.w(TAG, "$path failed: ${it.message}") }.getOrNull()
    }

    /**
     * Validates a candidate key against TMDB.
     *
     * Suspend + Dispatchers.IO on purpose: this used to be a blocking call made from a
     * viewModelScope coroutine (Main), so it threw NetworkOnMainThreadException, and the
     * surrounding runCatching reported every key as invalid.
     */
    suspend fun validate(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val res = Web.get("https://api.themoviedb.org/3/configuration?api_key=$key")
            AppLog.i(TAG, "tmdb key validation: HTTP ${res.code}")
            res.code in 200..299 && res.body.contains("images")
        }.getOrDefault(false)
    }

    suspend fun search(query: String, kind: String = "multi"): List<Item> {
        val o = get("/search/$kind", "&query=${java.net.URLEncoder.encode(query, "UTF-8")}&include_adult=false")
            ?: return emptyList()
        return o.arr("results")?.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val type = r.str("media_type") ?: if (kind == "movie") "movie" else "tv"
            Item(
                id = r.int("id") ?: return@mapNotNull null,
                title = r.str("title") ?: r.str("name").orEmpty(),
                year = (r.str("release_date") ?: r.str("first_air_date"))?.take(4)?.toIntOrNull(),
                overview = r.str("overview").orEmpty(),
                posterPath = r.str("poster_path"),
                imdbId = null,
                mediaType = type,
                voteAverage = (r["vote_average"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
            )
        } ?: emptyList()
    }

    /** Resolve the canonical title/year used for the folder layout (media-layout.ts tmdbResolve). */
    suspend fun resolveTitle(title: String, kind: String): Pair<String, Int?>? {
        val tmdbKind = if (kind == "series") "tv" else "movie"
        val o = get("/search/$tmdbKind", "&query=${java.net.URLEncoder.encode(title, "UTF-8")}&include_adult=false")
            ?: return null
        val first = (o.arr("results")?.firstOrNull()) as? JsonObject ?: return null
        val name = first.str("title") ?: first.str("name") ?: return null
        val year = (first.str("release_date") ?: first.str("first_air_date"))?.take(4)?.toIntOrNull()
        return name to year
    }

    /** Trending lists for the Discover tab. */
    suspend fun trending(kind: String): List<Item> {
        val o = get("/trending/$kind/week") ?: return emptyList()
        return o.arr("results")?.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val type = r.str("media_type") ?: if (kind == "movie") "movie" else "tv"
            Item(
                id = r.int("id") ?: return@mapNotNull null,
                title = r.str("title") ?: r.str("name").orEmpty(),
                year = (r.str("release_date") ?: r.str("first_air_date"))?.take(4)?.toIntOrNull(),
                overview = r.str("overview").orEmpty(),
                posterPath = r.str("poster_path"),
                imdbId = null,
                mediaType = type,
                voteAverage = (r["vote_average"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
            )
        } ?: emptyList()
    }

    /** IMDb id for a TMDB item (needed to ask Comet for streams). */
    suspend fun imdbId(tmdbId: Int, mediaType: String): String? {
        val kind = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val o = get("/$kind/$tmdbId/external_ids") ?: return null
        return o.str("imdb_id")
    }

    fun posterUrl(path: String?, size: String = "w342"): String? =
        path?.let { "https://image.tmdb.org/t/p/$size$it" }

    companion object { private const val TAG = "TMDB" }
}

/** Top-level poster URL helper (used from composables). */
fun tmdbPoster(path: String?, size: String = "w342"): String? =
    path?.let { "https://image.tmdb.org/t/p/$size$it" }
