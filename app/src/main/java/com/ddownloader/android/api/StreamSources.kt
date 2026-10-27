package com.ddownloader.android.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Multiple stream sources, mirroring the Windows app's `electron/latino-providers.py`:
 *
 *  1. **Comet**      — the embedded Comet add-on (the only source the app had until now).
 *  2. **Cinecalidad** — Torrentio filtered to the `cinecalidad` provider.
 *  3. **TCL**        — hacktorrent.to's WordPress REST API, searched by title (Spanish catalogue).
 *
 * All three resolve to a magnet-able info hash, so the debrid flow downstream is unchanged. They run
 * in parallel and the results are merged and de-duplicated by info hash; the label of the source is
 * carried in [CometApi.Stream.name] so the sheet can show where a row came from.
 *
 * Notes on fidelity vs the Electron implementation:
 *  • TCL is searched by the TMDB title we already have, instead of re-resolving titles through the
 *    IMDB suggestion API (one fewer network hop, same result).
 *  • TCL rows whose download link is an `acortalink.net` shortener are skipped rather than resolved;
 *    resolving those is a separate scraper. Everything else is handled.
 */
class StreamSources(private val comet: CometApi) {

    suspend fun resolve(
        type: String,
        imdbId: String,
        season: Int?,
        episode: Int?,
        title: String,
        year: Int?,
    ): List<CometApi.Stream> = coroutineScope {
        val jobs = listOf(
            async {
                comet.streams(type, imdbId, season, episode)
                    .getOrElse { emptyList() }
                    .map { it.copy(name = "Comet · ${it.name}") }
            },
            async { runCatching { cinecalidad(type, imdbId, season, episode) }.getOrElse { emptyList() } },
            async { runCatching { tcl(type, title, year, season, episode) }.getOrElse { emptyList() } },
        )
        val merged = jobs.awaitAll().flatten()
        merged
            .filter { !it.infoHash.isNullOrBlank() }
            .distinctBy { it.infoHash!!.lowercase() }
            .sortedByDescending { it.videoSize ?: 0L }
    }

    // ── Cinecalidad (via Torrentio) ─────────────────────────────────────────
    private suspend fun cinecalidad(
        type: String,
        imdbId: String,
        season: Int?,
        episode: Int?,
    ): List<CometApi.Stream> {
        val id = if (type == "series") "$imdbId:${season ?: 1}:${episode ?: 1}" else imdbId
        val res = Web.get("$CINECALIDAD/stream/$type/$id.json")
        if (res.code !in 200..299) return emptyList()
        val streams = runCatching {
            Json.parseToJsonElement(res.body).jsonObject["streams"]?.jsonArray
        }.getOrNull() ?: return emptyList()

        return streams.mapNotNull { el ->
            val o = el.jsonObject
            val hash = o["infoHash"]?.jsonPrimitive?.contentOrNull?.lowercase()
            if (hash.isNullOrBlank()) return@mapNotNull null
            val hints = o["behaviorHints"] as? JsonObject
            val filename = hints?.get("filename")?.jsonPrimitive?.contentOrNull
            val size = hints?.get("videoSize")?.jsonPrimitive?.longOrNull
            CometApi.Stream(
                name = "Cinecalidad",
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                infoHash = hash,
                fileIdx = o["fileIdx"]?.jsonPrimitive?.intOrNull,
                url = null,
                filename = filename,
                videoSize = size,
            )
        }
    }

    // ── TCL (hacktorrent.to WordPress REST API) ─────────────────────────────
    private suspend fun tcl(
        type: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): List<CometApi.Stream> {
        if (title.isBlank()) return emptyList()
        val contentTypes = if (type == "movie") listOf("pelicula") else listOf("serie", "anime")
        val wanted = normalise(title)

        var slug: String? = null
        var contentType = contentTypes.first()
        for (ct in contentTypes) {
            val res = Web.get(
                "$TCL_API/search?query=${enc(title)}&type=$ct&page=1",
                headers = mapOf("Referer" to "$TCL_HOST/"),
            )
            if (res.code !in 200..299) continue
            val results = runCatching {
                Json.parseToJsonElement(res.body).jsonObject["results"] as? JsonArray
            }.getOrNull() ?: continue
            for (el in results) {
                val o = el.jsonObject
                val itemTitle = normalise(o["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                if (itemTitle != wanted) continue
                val itemYear = o["year"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
                if (ct == "pelicula" && year != null && itemYear != null && itemYear != year) continue
                slug = o["slug"]?.jsonPrimitive?.contentOrNull
                contentType = ct
                break
            }
            if (slug != null) break
        }
        val found = slug ?: return emptyList()

        val endpoint = when {
            type == "movie" -> "movie"
            contentType == "anime" -> "anime"
            else -> "serie"
        }
        val detailRes = Web.get(
            "$TCL_API/$endpoint/${enc(found)}",
            headers = mapOf("Referer" to "$TCL_HOST/"),
        )
        if (detailRes.code !in 200..299) return emptyList()
        val downloads = runCatching {
            Json.parseToJsonElement(detailRes.body).jsonObject["downloads"] as? JsonArray
        }.getOrNull() ?: return emptyList()

        return downloads.mapNotNull { el ->
            val d = el.jsonObject
            if (type == "series") {
                val s = d["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val e = d["episode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (season != null && s != null && s != season) return@mapNotNull null
                if (episode != null && e != null && e != episode) return@mapNotNull null
            }
            val link = d["download_link"]?.jsonPrimitive?.contentOrNull
                ?: d["magnet"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            // A shortener needs another scraper; skipping is honest, silently failing is not.
            if (link.contains("acortalink", ignoreCase = true)) return@mapNotNull null
            val hash = HASH.find(link)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
            val quality = d["quality"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = buildString {
                append(d["title"]?.jsonPrimitive?.contentOrNull ?: title)
                if (quality.isNotBlank()) append(" [$quality]")
            }
            CometApi.Stream(
                name = "TCL" + if (quality.isNotBlank()) " · $quality" else "",
                title = label,
                infoHash = hash,
                fileIdx = null,
                url = null,
                filename = d["title"]?.jsonPrimitive?.contentOrNull,
                videoSize = parseSize(d["size"]?.jsonPrimitive?.contentOrNull),
            )
        }
    }

    private fun normalise(s: String): String =
        s.lowercase().replace(NON_ALNUM, " ").trim().replace(MULTI_SPACE, " ")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parseSize(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val m = SIZE.find(raw) ?: return null
        val value = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].uppercase()
        val mult = when (unit) {
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024
            "GB" -> 1024.0 * 1024 * 1024
            "TB" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (value * mult).toLong()
    }

    private companion object {
        const val TCL_HOST = "https://hacktorrent.to"
        const val TCL_API = "$TCL_HOST/wp-json/wpreact/v1"
        const val CINECALIDAD = "https://torrentio.strem.fun/providers=cinecalidad"
        val HASH = Regex("btih:([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE)
        val NON_ALNUM = Regex("[^a-z0-9]+")
        val MULTI_SPACE = Regex("\\s+")
        val SIZE = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([KMGT]B)", RegexOption.IGNORE_CASE)
    }
}
