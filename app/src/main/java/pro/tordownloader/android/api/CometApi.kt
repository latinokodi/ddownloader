package pro.tordownloader.android.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pro.tordownloader.android.BuildConfig
import pro.tordownloader.android.core.AppLog

/**
 * Client for the **embedded Comet add-on** (Stremio `stream` resource).
 *
 * The add-on base URL is `BuildConfig.COMET_BASE`, identical to the Electron
 * app's `CATALOG_BASE` after the 2026-10 replacement (previously a BetterPosters
 * catalog add-on). Comet declares `catalogs: []` and only `stream`, i.e. it does
 * not list content — it resolves torrent streams for a known IMDb id, which is
 * exactly what the debrid pipeline needs:
 *
 *   IMDb id → Comet stream (infoHash + filename + size) → magnet → TorBox/RD.
 */
class CometApi {

    data class Stream(
        val name: String,
        val title: String,
        val infoHash: String?,
        val fileIdx: Int?,
        val url: String?,
        val filename: String?,
        val videoSize: Long?,
    ) {
        /** Build the magnet link the debrid APIs expect. */
        fun magnet(): String? {
            val hash = infoHash ?: return null
            val dn = filename ?: title.ifBlank { "video" }
            return "magnet:?xt=urn:btih:$hash&dn=${java.net.URLEncoder.encode(dn, "UTF-8")}"
        }

        fun displayName(): String = filename?.takeIf { it.isNotBlank() } ?: title.ifBlank { name }
    }

    private val base: String get() = BuildConfig.COMET_BASE.trimEnd('/')

    fun manifest(): Result<JsonObject> = runCatching {
        val res = Web.get("$base/manifest.json")
        if (res.code !in 200..299) error("HTTP ${res.code}")
        Web.json.parseToJsonElement(res.body).jsonObject
    }.onFailure { AppLog.w(TAG, "manifest failed: ${it.message}") }

    /**
     * GET {base}/stream/{type}/{id}.json
     * Series require `tt123:season:episode`; the ids are validated before
     * interpolation so a caller cannot inject a path segment.
     */
    fun streams(type: String, imdbId: String, season: Int? = null, episode: Int? = null): Result<List<Stream>> = runCatching {
        require(type == "movie" || type == "series") { "type must be movie|series" }
        require(Regex("^tt\\d{5,10}$").matches(imdbId)) { "invalid imdb id" }
        val id = if (type == "series") {
            require(season != null && episode != null && season >= 1 && episode >= 1) {
                "series streams need season+episode"
            }
            "$imdbId:$season:$episode"
        } else {
            imdbId
        }
        val res = Web.get("$base/stream/$type/$id.json")
        if (res.code !in 200..299) error("HTTP ${res.code}")
        val root = Web.json.parseToJsonElement(res.body).jsonObject
        root.arr("streams")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val hints = o["behaviorHints"] as? JsonObject
            Stream(
                name = o.str("name").orEmpty(),
                title = o.str("title").orEmpty(),
                infoHash = o.str("infoHash"),
                fileIdx = o.int("fileIdx"),
                url = o.str("url"),
                filename = hints?.str("filename"),
                videoSize = hints?.long("videoSize"),
            )
        } ?: emptyList()
    }.onFailure { AppLog.w(TAG, "streams($type/$imdbId) failed: ${it.message}") }

    companion object { private const val TAG = "Comet" }
}
