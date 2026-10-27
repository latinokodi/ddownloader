package com.ddownloader.android.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client for a Stremio **catalog add-on**, mirroring the Windows app (`electron/main.ts`,
 * "Stremio Catalog Addon"): `/manifest.json`, `/catalog/{type}/{id}.json`, `/meta/{type}/{imdbId}.json`.
 *
 * The base URL is configurable (Settings → catalogue add-on) because the embedded Comet share
 * declares `catalogs: []` — it only serves `stream`. Point this at a catalogue-capable add-on and
 * Discover gains its rows; leave it at the default and the catalogue tab explains why it is empty.
 */
class CatalogAddon {

    @Volatile
    private var base: String = ""

    fun setBase(url: String) {
        base = url.trim().trimEnd('/')
    }

    val hasBase: Boolean get() = base.isNotBlank()

    data class Catalog(val type: String, val id: String, val name: String)

    data class Item(
        val id: String,
        val type: String,
        val name: String,
        val year: Int?,
        val poster: String?,
        val rating: Double?,
        val description: String?,
    )

    data class Video(val season: Int, val episode: Int, val title: String?)

    suspend fun catalogs(): Result<List<Catalog>> = runCatching {
        if (!hasBase) return@runCatching emptyList()
        val res = Web.get("$base/manifest.json")
        if (res.code !in 200..299) return@runCatching emptyList()
        val root = Json.parseToJsonElement(res.body).jsonObject
        val arr = root["catalogs"] as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: id
            Catalog(type, id, name)
        }
    }

    suspend fun items(type: String, catalogId: String): Result<List<Item>> = runCatching {
        if (!hasBase) return@runCatching emptyList()
        val res = Web.get("$base/catalog/$type/$catalogId.json")
        if (res.code !in 200..299) return@runCatching emptyList()
        val root = Json.parseToJsonElement(res.body).jsonObject
        val arr = root["metas"] as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Item(
                id = id,
                type = o["type"]?.jsonPrimitive?.contentOrNull ?: type,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                year = o["releaseInfo"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull(),
                poster = o["poster"]?.jsonPrimitive?.contentOrNull,
                rating = o["imdbRating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                description = o["description"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** Episode list for a series, from the add-on's `meta` resource. */
    suspend fun videos(type: String, imdbId: String): Result<List<Video>> = runCatching {
        if (!hasBase) return@runCatching emptyList()
        val res = Web.get("$base/meta/$type/$imdbId.json")
        if (res.code !in 200..299) return@runCatching emptyList()
        val root = Json.parseToJsonElement(res.body).jsonObject
        val meta = root["meta"] as? JsonObject ?: return@runCatching emptyList()
        val arr = meta["videos"] as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val season = o["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val episode = o["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: o["episode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: return@mapNotNull null
            Video(season, episode, o["title"]?.jsonPrimitive?.contentOrNull)
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}
