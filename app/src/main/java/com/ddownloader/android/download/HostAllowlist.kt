package com.ddownloader.android.download

/**
 * Host allowlist for debrid downloads — the Android twin of the Electron
 * `TORBOX_CDN_DOMAINS` / `REALDEBRID_CDN_DOMAINS` guard in electron/downloader.ts.
 *
 * Difference (deliberate, documented in DECISIONS.md): the check runs on EVERY
 * redirect hop, not just the first URL, because the original validated `url`
 * before handing it to axios while axios silently followed 3xx responses.
 */
object HostAllowlist {

    private val TORBOX = listOf(
        "dl.torbox.app", "cdn.torbox.app", "torbox.app", "media.torbox.app",
        "tb-cdn.io", "tb-cdn.cx",
    )
    private val REALDEBRID = listOf("real-debrid.com", "rd.nu")

    fun isAllowed(service: String, host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        if (com.ddownloader.android.data.Net.isLoopback(h)) return true
        val list = when (service) {
            "torbox" -> TORBOX
            "realdebrid" -> REALDEBRID
            // A URL the user pasted themselves: there is no service to pin to.
            // The invariant that still holds is "Tor only" (enforced in Net).
            "direct" -> return true
            else -> return true
        }
        return list.any { h == it || h.endsWith(".$it") }
    }

    fun allowedHosts(service: String): List<String> = when (service) {
        "torbox" -> TORBOX
        "realdebrid" -> REALDEBRID
        else -> emptyList()
    }
}
