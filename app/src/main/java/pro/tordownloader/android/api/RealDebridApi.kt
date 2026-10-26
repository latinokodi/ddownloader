package pro.tordownloader.android.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pro.tordownloader.android.core.AppLog
import pro.tordownloader.android.data.SettingsRepository

/**
 * Real-Debrid API — port of `electron/realdebrid.ts`.
 * OAuth v2 device-code flow + REST 1.0 endpoints.
 */
class RealDebridApi(private val settings: SettingsRepository) {

    data class TorrentInfo(
        val id: String,
        val filename: String,
        val status: String,
        val bytes: Long,
        val links: List<String>,
        val files: List<FileEntry>,
    )

    data class FileEntry(val id: String, val path: String, val bytes: Long, val selected: Boolean)

    data class Unrestricted(val download: String, val filename: String, val filesize: Long, val host: String)

    private fun token(): String = settings.settings.value.realdebridToken

    private fun auth(): Map<String, String> = mapOf("Authorization" to "Bearer ${token()}")

    fun deviceCode(clientId: String): Result<JsonObject> = runCatching {
        val res = Web.get("$OAUTH/device/code?client_id=$clientId&new_credentials=yes")
        if (res.code !in 200..299) error("HTTP ${res.code}")
        Web.json.parseToJsonElement(res.body).jsonObject
    }

    fun credentials(clientId: String, code: String): Result<JsonObject> = runCatching {
        val res = Web.get("$OAUTH/device/credentials?client_id=$clientId&code=$code")
        if (res.code !in 200..299) error("HTTP ${res.code}")
        Web.json.parseToJsonElement(res.body).jsonObject
    }

    fun token(clientId: String, clientSecret: String, code: String): Result<JsonObject> = runCatching {
        val res = Web.postForm(
            "$OAUTH/token",
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "code" to code,
                "grant_type" to "http://oauth.net/grant_type/device/1.0",
            ),
        )
        if (res.code !in 200..299) error("HTTP ${res.code}: ${res.body.take(160)}")
        Web.json.parseToJsonElement(res.body).jsonObject
    }

    fun user(): Result<JsonObject> = runCatching {
        val res = Web.get("$REST/user", headers = auth())
        if (res.code !in 200..299) error("HTTP ${res.code}")
        Web.json.parseToJsonElement(res.body).jsonObject
    }

    fun listTorrents(): Result<List<JsonObject>> = runCatching {
        val res = Web.get("$REST/torrents", headers = auth())
        if (res.code !in 200..299) error("HTTP ${res.code}")
        (Web.json.parseToJsonElement(res.body) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    fun addMagnet(magnet: String): Result<String> = runCatching {
        val res = Web.postForm("$REST/torrents/addMagnet", mapOf("magnet" to magnet), auth())
        if (res.code !in 200..299) error("HTTP ${res.code}")
        Web.json.parseToJsonElement(res.body).jsonObject.str("id") ?: error("no id")
    }

    fun torrentInfo(id: String): Result<TorrentInfo> = runCatching {
        val res = Web.get("$REST/torrents/info/$id", headers = auth())
        if (res.code !in 200..299) error("HTTP ${res.code}")
        val o = Web.json.parseToJsonElement(res.body).jsonObject
        TorrentInfo(
            id = o.str("id").orEmpty(),
            filename = o.str("filename").orEmpty(),
            status = o.str("status").orEmpty(),
            bytes = o.long("bytes") ?: 0L,
            links = o.arr("links")?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: emptyList(),
            files = o.arr("files")?.mapNotNull { f ->
                val fo = f as? JsonObject ?: return@mapNotNull null
                FileEntry(
                    id = fo.str("id").orEmpty(),
                    path = fo.str("path").orEmpty(),
                    bytes = fo.long("bytes") ?: 0L,
                    selected = fo.int("selected") == 1,
                )
            } ?: emptyList(),
        )
    }

    fun selectAllFiles(id: String, fileIds: List<String>): Result<Unit> = runCatching {
        val res = Web.postForm("$REST/torrents/selectFiles/$id", mapOf("files" to fileIds.joinToString(",")), auth())
        if (res.code !in 200..299 && res.code != 204) error("HTTP ${res.code}")
    }

    fun deleteTorrent(id: String): Result<Unit> = runCatching {
        val res = Web.formDelete("$REST/torrents/delete/$id", auth())
        if (res.code !in 200..299 && res.code != 204) error("HTTP ${res.code}")
    }

    fun unrestrict(link: String): Result<Unrestricted> = runCatching {
        val res = Web.postForm("$REST/unrestrict/link", mapOf("link" to link), auth())
        if (res.code !in 200..299) error("HTTP ${res.code}: ${res.body.take(160)}")
        val o = Web.json.parseToJsonElement(res.body).jsonObject
        Unrestricted(
            download = o.str("download") ?: error("no download url"),
            filename = o.str("filename").orEmpty(),
            filesize = o.long("filesize") ?: 0L,
            host = o.str("host").orEmpty(),
        )
    }

    companion object {
        private const val TAG = "RealDebrid"
        private const val OAUTH = "https://api.real-debrid.com/oauth/v2"
        private const val REST = "https://api.real-debrid.com/rest/1.0"
        const val OPENSOURCE_CLIENT_ID = "X245A4XAIBGVM"
    }
}
