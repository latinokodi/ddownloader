package com.ddownloader.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import com.ddownloader.android.data.Net
import java.io.IOException
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shared JSON + HTTP helpers for the service clients.
 *
 * **Every request here is `suspend` and hops to [Dispatchers.IO] itself.** These helpers used
 * to be plain blocking functions, which meant each caller had to remember to be off the main
 * thread. Five separate features broke because they didn't (`NetworkOnMainThreadException`,
 * usually surfacing to the user as "no results" or "<action>: null"): TMDB validate, account
 * token checks, the update check, the settings backup and finally Discover/Search and
 * "add magnet". Owning the dispatcher in one place makes that mistake impossible to repeat —
 * a caller cannot invoke these without being in a coroutine.
 */
internal object Web {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    const val JSON_MEDIA = "application/json; charset=utf-8"

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowedHosts: List<String> = emptyList(),
    ): Net.HttpResult = withContext(Dispatchers.IO) {
        Net.get(
            url = url,
            headers = headers,
            allowedHost = { host ->
                allowedHosts.isEmpty() || allowedHosts.any { host.equals(it, true) || host.endsWith(".$it", true) }
            },
        )
    }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Net.HttpResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA.toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        execute(req)
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Net.HttpResult = withContext(Dispatchers.IO) {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        val req = Request.Builder()
            .url(url)
            .post(fb.build())
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        execute(req)
    }

    suspend fun formDelete(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Net.HttpResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .delete()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        execute(req)
    }

    /**
     * Multipart POST. TorBox's createtorrent wants `magnet` plus the P2P-suppression flags
     * (`seed=0`, `allow_zip=false`) that the Electron client also sends, so the caller passes
     * an ordered field map rather than a single value.
     */
    suspend fun postMultipartFields(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String>,
    ): Net.HttpResult = withContext(Dispatchers.IO) {
        val boundary = "----TD${System.currentTimeMillis()}"
        val body = buildString {
            fields.forEach { (name, value) ->
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                append(value)
                append("\r\n")
            }
            append("--$boundary--\r\n")
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        execute(req)
    }

    /** Runs one request through the redirect-validating client and reads the body. */
    private fun execute(req: Request): Net.HttpResult {
        Net.follow(req, allowedHost = { true }).use { resp ->
            // A null-message NetworkOnMainThreadException used to be indistinguishable from a
            // real failure; an explicit check makes that impossible from here on.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                throw IOException("blocking HTTP on the main thread for ${req.url.host} — call from Dispatchers.IO")
            }
            return Net.HttpResult(
                resp.code,
                resp.body?.string().orEmpty(),
                resp.request.url.toString(),
                resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
            )
        }
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
