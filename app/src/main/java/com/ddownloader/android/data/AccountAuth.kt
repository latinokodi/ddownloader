package com.ddownloader.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.ddownloader.android.api.RealDebridApi
import com.ddownloader.android.api.TorBoxApi
import com.ddownloader.android.api.str
import com.ddownloader.android.core.AppLog

/**
 * Debrid authorization by **API token** (there is no OAuth device flow).
 *
 * The user pastes the token from the provider's own web UI:
 *  • TorBox      → Settings → API → token
 *  • Real-Debrid → https://real-debrid.com/devices → private API token
 *
 * The token authorizes every call, so storing it is not the same as being authorized:
 * each token is *proved* against the provider's authenticated endpoint (`/user/me`
 * for TorBox, `/user` for Real-Debrid) and the outcome is surfaced as [AccountState].
 * 401/403 = rejected; a transport failure = saved but not verifiable right now.
 *
 * Network work runs on [Dispatchers.IO]; calling it directly from a ViewModel scope
 * (Main) threw NetworkOnMainThreadException in the first shipped build — the hop here
 * is the fix, not decoration.
 */
class AccountAuth(
    private val settings: SettingsRepository,
    private val torbox: TorBoxApi,
    private val realdebrid: RealDebridApi,
) {
    sealed interface AccountState {
        data object NotConfigured : AccountState
        data object Checking : AccountState
        data class Authorized(val account: String, val detail: String) : AccountState
        /** Saved, but the provider rejected it (HTTP 401/403). */
        data class Rejected(val reason: String) : AccountState
        /** Saved, but the check could not run (offline / provider down). */
        data class Unverified(val reason: String) : AccountState
    }

    private val _torbox = MutableStateFlow<AccountState>(AccountState.NotConfigured)
    val torboxState: StateFlow<AccountState> = _torbox.asStateFlow()

    private val _realdebrid = MutableStateFlow<AccountState>(AccountState.NotConfigured)
    val realdebridState: StateFlow<AccountState> = _realdebrid.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        if (cfg.hasTorBox) validateTorBox() else _torbox.value = AccountState.NotConfigured
        if (cfg.hasRealDebrid) validateRealDebrid() else _realdebrid.value = AccountState.NotConfigured
    }

    suspend fun validateTorBox(): AccountState = withContext(Dispatchers.IO) {
        _torbox.value = AccountState.Checking
        val state = torbox.userInfo().fold(
            onSuccess = { o ->
                val account = o.str("email") ?: o.str("username") ?: o.str("id") ?: "TorBox account"
                val plan = when (o.str("plan")) {
                    "0" -> "free"
                    "1" -> "essential"
                    "2" -> "pro"
                    "3" -> "standard"
                    else -> o.str("plan")?.let { "plan $it" } ?: ""
                }
                val expiry = o.str("premium_expires_at") ?: o.str("expires_at") ?: o.str("expiration")
                AccountState.Authorized(
                    account = account,
                    detail = listOfNotNull(plan.ifBlank { null }, expiry?.let { "expires ${it.take(10)}" })
                        .joinToString(" • ").ifBlank { "token accepted" },
                )
            },
            onFailure = { classify(it) },
        )
        AppLog.i(TAG, "torbox token check: ${state::class.simpleName}")
        _torbox.value = state
        state
    }

    suspend fun validateRealDebrid(): AccountState = withContext(Dispatchers.IO) {
        _realdebrid.value = AccountState.Checking
        val state = realdebrid.user().fold(
            onSuccess = { o ->
                val account = o.str("username") ?: o.str("email") ?: "Real-Debrid account"
                val type = o.str("type").orEmpty()
                val expiry = o.str("expiration")
                AccountState.Authorized(
                    account = account,
                    detail = listOfNotNull(type.ifBlank { null }, expiry?.let { "expires ${it.take(10)}" })
                        .joinToString(" • ").ifBlank { "token accepted" },
                )
            },
            onFailure = { classify(it) },
        )
        AppLog.i(TAG, "real-debrid token check: ${state::class.simpleName}")
        _realdebrid.value = state
        state
    }

    private fun classify(t: Throwable): AccountState {
        val msg = t.message.orEmpty()
        val rejected = msg.contains("HTTP 401") || msg.contains("HTTP 403") ||
            msg.contains("bad_token", true) || msg.contains("Unauthorized", true) ||
            msg.contains("invalid", true)
        return if (rejected) AccountState.Rejected(msg) else AccountState.Unverified(msg)
    }

    companion object {
        private const val TAG = "AccountAuth"
    }
}
