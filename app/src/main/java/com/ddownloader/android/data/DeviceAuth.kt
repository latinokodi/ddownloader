package com.ddownloader.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import com.ddownloader.android.api.Web
import com.ddownloader.android.api.str
import com.ddownloader.android.core.AppLog

/**
 * Debrid accounts are added by **URL + code**, never by pasting an API token.
 *
 * Both providers expose an RFC-8628-style device authorization flow; the app asks for a
 * code, shows the user the verification URL and the code, and polls until the user has
 * authorized the device in the browser. The credential the provider hands back is then
 * stored (encrypted) — the user never handles it.
 *
 *  • TorBox      GET  /v1/api/user/auth/device/start?app=…      → device_code, code, verification_url
 *                POST /v1/api/user/auth/device/token {device_code} → the API token
 *  • Real-Debrid GET  /oauth/v2/device/code?client_id=…&new_credentials=yes
 *                GET  /oauth/v2/device/credentials?client_id&code → per-user client_id/secret
 *                POST /oauth/v2/token (device_code grant)          → access_token + refresh_token
 *
 * Every call runs on [Dispatchers.IO]; the poll loop itself lives in the ViewModel.
 */
class DeviceAuth(private val settings: SettingsRepository) {

    enum class Provider(val label: String, val accountHint: String) {
        TORBOX("TorBox", "torbox.app"),
        REALDEBRID("Real-Debrid", "real-debrid.com/device"),
    }

    data class Prompt(
        val provider: Provider,
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
    )

    sealed interface Outcome {
        /** The user has not authorized in the browser yet. */
        data object Pending : Outcome

        /**
         * Authorization finished. [token] is the credential to store (encrypted) and is
         * never shown in the UI; the Real-Debrid fields are kept so the token can be
         * refreshed later without asking the user to pair again.
         */
        data class Linked(
            val provider: Provider,
            val account: String,
            val token: String,
            val refreshToken: String? = null,
            val clientId: String? = null,
            val clientSecret: String? = null,
        ) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    suspend fun start(provider: Provider): Result<Prompt> = withContext(Dispatchers.IO) {
        when (provider) {
            Provider.TORBOX -> startTorBox()
            Provider.REALDEBRID -> startRealDebrid()
        }
    }

    suspend fun poll(prompt: Prompt): Outcome = withContext(Dispatchers.IO) {
        when (prompt.provider) {
            Provider.TORBOX -> pollTorBox(prompt)
            Provider.REALDEBRID -> pollRealDebrid(prompt)
        }
    }

    // ── TorBox ─────────────────────────────────────────────────────────────
    private fun startTorBox(): Result<Prompt> = runCatching {
        val res = Net.get(
            url = "$TORBOX/api/user/auth/device/start?app=DDownloader",
            headers = mapOf("User-Agent" to "DDownloader"),
            client = Net.probeClient,
            allowedHost = { it == "api.torbox.app" },
        )
        val o = Web.json.parseToJsonElement(res.body).jsonObject
        if (res.code !in 200..299 || o["success"]?.toString() == "false") {
            error("TorBox did not issue a code (HTTP ${res.code})")
        }
        val d = o["data"]?.jsonObject ?: error("unexpected TorBox response")
        AppLog.d(TAG, "torbox verification_url (not shown): ${d.str("verification_url")}")
        Prompt(
            provider = Provider.TORBOX,
            deviceCode = d.str("device_code") ?: error("no device_code"),
            userCode = d.str("code") ?: error("no code"),
            // The API hands back the page that accepts the code, e.g.
            // https://torbox.app/oauth/device?app=DDownloader (the app name prefills the page).
            // tor.box/link is the same page behind a shortlink, used only as a fallback.
            // Owner preference: present the SHORT link; tor.box/link redirects to the same
            // device page. The long URL from the API is only logged (see above).
            verificationUrl = TORBOX_SHORTLINK,
            expiresInSeconds = d.str("expires_in")?.toIntOrNull() ?: 600,
            intervalSeconds = d.str("interval")?.toIntOrNull()?.coerceIn(2, 30) ?: 5,
        )
    }.onFailure { AppLog.e(TAG, "torbox device start failed: ${it.message}", it) }

    private fun pollTorBox(prompt: Prompt): Outcome {
        val body = """{"device_code":"${prompt.deviceCode}"}"""
        val res = try {
            Web.postJson("$TORBOX/api/user/auth/device/token", body, mapOf("User-Agent" to "DDownloader"))
        } catch (t: Throwable) {
            return Outcome.Failed(t.message ?: "network error")
        }
        val root = runCatching { Web.json.parseToJsonElement(res.body).jsonObject }.getOrNull()
        val success = root?.str("success")?.toBooleanStrictOrNull() ?: (res.code in 200..299)
        val errorCode = root?.str("error").orEmpty()
        // The API answers 200 even while the user has not authorized yet, and describes
        // the state in `error`: DEVICE_CODE_NOT_USED = keep polling, ITEM_NOT_FOUND =
        // expired, DEVICE_CODE_USED = already collected. Treating any 200 as success was
        // the bug that produced "authorized but returned no token".
        val dataKeys = (root?.get("data") as? kotlinx.serialization.json.JsonObject)?.keys?.joinToString(",")
        AppLog.d(TAG, "torbox device token: http=${res.code} success=$success error=${errorCode.blank() } dataKeys=$dataKeys")

        if (res.code in 200..299 && success) {
            val token = extractToken(root)
            if (!token.isNullOrBlank()) {
                AppLog.i(TAG, "torbox device authorized (token ${token.length} chars)")
                return Outcome.Linked(Provider.TORBOX, "TorBox account", token)
            }
            AppLog.w(TAG, "torbox authorized but no token found; data keys=$dataKeys body=${res.body.take(200)}")
            return Outcome.Failed(
                "TorBox authorized the device but the response had no token " +
                    "(keys: ${dataKeys ?: "none"}). Send the Logs screen output and this gets fixed.",
            )
        }

        return when (errorCode.uppercase()) {
            "DEVICE_CODE_NOT_USED" -> Outcome.Pending
            "ITEM_NOT_FOUND" -> Outcome.Failed("The code expired before it was authorized — start again.")
            "DEVICE_CODE_USED" -> Outcome.Failed("That code was already collected — start again to get a new one.")
            "" -> if (res.code in 400..499) Outcome.Failed("TorBox returned HTTP ${res.code}") else Outcome.Pending
            else -> Outcome.Failed("TorBox returned $errorCode")
        }
    }

    /**
     * Finds the credential wherever the provider put it. The API documents no 200 schema,
     * so this walks the response instead of assuming one shape: known key names first
     * (at the top level and inside `data`), then any token-shaped string, then a recursive
     * search for token-ish keys. `data` may also be the token itself as a plain string.
     */
    private fun extractToken(root: JsonObject?): String? {
        if (root == null) return null
        val tokenKeys = listOf("token", "api_key", "apikey", "access_token", "accessToken", "session_token")

        fun primitive(o: JsonObject?, key: String): String? =
            (o?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        // 1) top level
        tokenKeys.forEach { k -> primitive(root, k)?.let { return it } }
        // 2) direct child of data (object, or data itself as a string)
        when (val data = root["data"]) {
            is JsonPrimitive -> data.contentOrNull?.trim()
                ?.takeIf { it.length >= 16 && it.none(Char::isWhitespace) }?.let { return it }
            is JsonObject -> {
                tokenKeys.forEach { k -> primitive(data, k)?.let { return it } }
                // 3) one level deeper: data.<something>.token / .api_key
                data.values.forEach { v ->
                    (v as? JsonObject)?.let { inner ->
                        tokenKeys.forEach { k -> primitive(inner, k)?.let { return it } }
                    }
                }
                // 4) recursive search for a token-ish key anywhere under data
                fun walk(o: JsonObject, depth: Int): String? {
                    if (depth > 3) return null
                    tokenKeys.forEach { k -> primitive(o, k)?.let { return it } }
                    o.values.forEach { v -> (v as? JsonObject)?.let { r -> walk(r, depth + 1)?.let { return it } } }
                    return null
                }
                walk(data, 0)?.let { return it }
                // 5) last resort: the only long opaque string in data
                val candidates = data.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter { it.length in 24..512 && it.none(Char::isWhitespace) }
                if (candidates.size == 1) return candidates.first()
            }
            is kotlinx.serialization.json.JsonArray -> {
                data.forEach { el ->
                    (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.length >= 16 }?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun String.blank(): String = if (isEmpty()) "-" else this

    // ── Real-Debrid ────────────────────────────────────────────────────────
    private fun startRealDebrid(): Result<Prompt> = runCatching {
        val res = Net.get(
            url = "$RD/device/code?client_id=$RD_CLIENT_ID&new_credentials=yes",
            headers = mapOf("User-Agent" to "DDownloader"),
            client = Net.probeClient,
            allowedHost = { it == "api.real-debrid.com" },
        )
        if (res.code !in 200..299) error("Real-Debrid did not issue a code (HTTP ${res.code})")
        val o = Web.json.parseToJsonElement(res.body).jsonObject
        Prompt(
            provider = Provider.REALDEBRID,
            deviceCode = o.str("device_code") ?: error("no device_code"),
            userCode = o.str("user_code") ?: error("no user_code"),
            verificationUrl = o.str("verification_url") ?: "https://real-debrid.com/device",
            expiresInSeconds = o.str("expires_in")?.toIntOrNull() ?: 900,
            intervalSeconds = o.str("interval")?.toIntOrNull()?.coerceIn(2, 30) ?: 5,
        )
    }.onFailure { AppLog.e(TAG, "real-debrid device start failed: ${it.message}", it) }

    private fun pollRealDebrid(prompt: Prompt): Outcome {
        // Step 2: the user authorized? Then we get per-user client credentials.
        val credRes = try {
            Net.get(
                url = "$RD/device/credentials?client_id=$RD_CLIENT_ID&code=${prompt.deviceCode}",
                headers = mapOf("User-Agent" to "DDownloader"),
                client = Net.probeClient,
                allowedHost = { it == "api.real-debrid.com" },
            )
        } catch (t: Throwable) {
            return Outcome.Failed(t.message ?: "network error")
        }
        if (credRes.code == 403 || credRes.code == 400) return Outcome.Pending
        if (credRes.code !in 200..299) return Outcome.Failed("credentials HTTP ${credRes.code}")
        val cred = runCatching { Web.json.parseToJsonElement(credRes.body).jsonObject }.getOrNull()
            ?: return Outcome.Failed("unreadable credentials")
        val clientId = cred.str("client_id") ?: return Outcome.Pending
        val clientSecret = cred.str("client_secret") ?: return Outcome.Pending

        // Step 3: exchange for the access token.
        val tokenRes = try {
            Web.postForm(
                "$RD/token",
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "code" to prompt.deviceCode,
                    "grant_type" to "http://oauth.net/grant_type/device/1.0",
                ),
                mapOf("User-Agent" to "DDownloader"),
            )
        } catch (t: Throwable) {
            return Outcome.Failed(t.message ?: "token exchange failed")
        }
        if (tokenRes.code !in 200..299) return Outcome.Failed("token HTTP ${tokenRes.code}")
        val tok = runCatching { Web.json.parseToJsonElement(tokenRes.body).jsonObject }.getOrNull()
            ?: return Outcome.Failed("unreadable token response")
        val access = tok.str("access_token") ?: return Outcome.Failed("no access_token")
        AppLog.i(TAG, "real-debrid device authorized")
        return Outcome.Linked(
            provider = Provider.REALDEBRID,
            account = cred.str("username") ?: cred.str("email") ?: "Real-Debrid account",
            token = access,
            refreshToken = tok.str("refresh_token"),
            clientId = clientId,
            clientSecret = clientSecret,
        )
    }

    companion object {
        private const val TAG = "DeviceAuth"
        private const val TORBOX = "https://api.torbox.app/v1"
        /** Shortlink that redirects to https://torbox.app/oauth/device. */
        internal const val TORBOX_SHORTLINK = "https://tor.box/link"
        private const val RD = "https://api.real-debrid.com/oauth/v2"
        /** Public client id used by open-source Real-Debrid integrations (device flow). */
        internal const val RD_CLIENT_ID = "X245A4XAIBGVM"
    }
}
