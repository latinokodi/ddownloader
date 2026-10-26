package com.ddownloader.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        Prompt(
            provider = Provider.TORBOX,
            deviceCode = d.str("device_code") ?: error("no device_code"),
            userCode = d.str("code") ?: error("no code"),
            // The API hands back the page that accepts the code, e.g.
            // https://torbox.app/oauth/device?app=DDownloader (the app name prefills the page).
            // tor.box/link is the same page behind a shortlink, used only as a fallback.
            verificationUrl = d.str("verification_url")?.takeIf { it.isNotBlank() } ?: TORBOX_SHORTLINK,
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
        return when {
            res.code == 200 -> {
                val o = runCatching { Web.json.parseToJsonElement(res.body).jsonObject }.getOrNull()
                val data = o?.get("data")?.jsonObject
                // The credential may be returned as `token` or `api_key` depending on the app.
                val token = data?.str("token") ?: data?.str("api_key")
                if (token.isNullOrBlank()) {
                    Outcome.Failed("TorBox authorized but returned no token")
                } else {
                    AppLog.i(TAG, "torbox device authorized (token received, ${token.length} chars)")
                    Outcome.Linked(Provider.TORBOX, "TorBox account", token)
                }
            }
            res.code == 400 || res.code == 403 || res.code == 404 || res.code == 428 -> {
                // 404/400 while pending is what the API returns before the code is entered.
                Outcome.Pending
            }
            else -> Outcome.Failed("HTTP ${res.code}")
        }
    }

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
