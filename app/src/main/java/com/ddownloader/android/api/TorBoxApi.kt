package com.ddownloader.android.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.data.SettingsRepository
import java.net.URLEncoder

/**
 * TorBox API — port of `electron/torbox.ts`.
 * Base: https://api.torbox.app/v1/api, auth: `Authorization: Bearer <token>`.
 */
class TorBoxApi(private val settings: SettingsRepository) {

    data class Torrent(
        val id: String,
        val hash: String,
        val name: String,
        val state: String,
        val progress: Double,
        val size: Long,
        val files: List<TorrentFile>,
    )

    data class TorrentFile(val id: String, val name: String, val size: Long)

    private fun token(): String = settings.settings.value.torboxToken

    private fun auth(): Map<String, String> = mapOf("Authorization" to "Bearer ${token()}")

    private fun api(path: String, query: Map<String, String> = emptyMap()): String {
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "https://api.torbox.app/v1/api$path$q"
    }

    /** GET /user/auth/device/start — device-code flow, step 1. */
    suspend fun deviceStart(): Result<JsonObject> = runCatching {
        val res = Web.get(api("/user/auth/device/start"))
        if (res.code !in 200..299) error("HTTP ${res.code}")
        Web.json.parseToJsonElement(res.body).jsonObject
    }.onFailure { AppLog.w(TAG, "device start failed: ${it.message}") }

    /** POST /user/auth/device/token — step 2; returns the access token. */
    suspend fun deviceToken(deviceCode: String): Result<String> = runCatching {
        val res = Web.postJson(
            api("/user/auth/device/token"),
            """{"device_code":"$deviceCode"}""",
        )
        if (res.code !in 200..299) error("HTTP ${res.code}")
        val root = Web.json.parseToJsonElement(res.body).jsonObject
        val data = root["data"]
        when (data) {
            is kotlinx.serialization.json.JsonPrimitive -> data.content
            is JsonObject -> data.str("access_token") ?: data.str("token") ?: error("no token in response")
            else -> root.str("access_token") ?: error("no token in response")
        }
    }

    /** GET /user/me */
    suspend fun userInfo(): Result<JsonObject> = runCatching {
        val res = Web.get(api("/user/me"), headers = auth())
        if (res.code !in 200..299) error("HTTP ${res.code}")
        (Web.json.parseToJsonElement(res.body).jsonObject["data"] as? JsonObject)
            ?: Web.json.parseToJsonElement(res.body).jsonObject
    }

    /** GET /torrents/mylist */
    suspend fun listTorrents(): Result<List<Torrent>> = runCatching {
        val res = Web.get(api("/torrents/mylist", mapOf("bypass_cache" to "true")), headers = auth())
        if (res.code !in 200..299) error("HTTP ${res.code}")
        val root = Web.json.parseToJsonElement(res.body).jsonObject
        val arr = root.arr("data") ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Torrent(
                id = o.str("id").orEmpty(),
                hash = o.str("hash").orEmpty(),
                name = o.str("name").orEmpty(),
                state = o.str("download_state").orEmpty(),
                progress = (o["progress"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
                size = o.long("size") ?: 0L,
                files = o.arr("files")?.mapNotNull { f ->
                    val fo = f as? JsonObject ?: return@mapNotNull null
                    TorrentFile(fo.str("id").orEmpty(), fo.str("name") ?: fo.str("short_name").orEmpty(), fo.long("size") ?: 0L)
                } ?: emptyList(),
            )
        }
    }

    /** POST /torrents/createtorrent (multipart magnet) */
    suspend fun addMagnet(magnet: String): Result<String> = runCatching {
        // seed=0 + allow_zip=false: never seed (no P2P from this app) and never ask
        // TorBox to zip the payload (parity with electron/torbox.ts:93,117).
        val res = Web.postMultipartFields(
            api("/torrents/createtorrent"),
            linkedMapOf("magnet" to magnet, "seed" to "0", "allow_zip" to "false"),
            auth(),
        )
        if (res.code !in 200..299) error("HTTP ${res.code}: ${res.body.take(200)}")
        val root = Web.json.parseToJsonElement(res.body).jsonObject
        (root["data"] as? JsonObject)?.str("torrent_id")
            ?: (root["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: error("no torrent id")
    }

    /** GET /torrents/requestdl → signed direct URL. */
    suspend fun requestDownloadLink(torrentId: String, fileId: String): Result<String> = runCatching {
        val res = Web.get(
            api(
                "/torrents/requestdl",
                mapOf("torrent_id" to torrentId, "file_id" to fileId, "zip_link" to "false"),
            ),
            headers = auth(),
        )
        if (res.code !in 200..299) error("HTTP ${res.code}")
        val root = Web.json.parseToJsonElement(res.body).jsonObject
        root["data"] as? kotlinx.serialization.json.JsonPrimitive
            ?: error("no url")
    }.map { (it as kotlinx.serialization.json.JsonPrimitive).content }

    suspend fun deleteTorrent(torrentId: String): Result<Unit> = runCatching {
        val res = Web.postJson(api("/torrents/controltorrent"), """{"torrent_id":"$torrentId","operation":"Delete"}""", auth())
        if (res.code !in 200..299) error("HTTP ${res.code}")
    }

    companion object { private const val TAG = "TorBox" }
}
