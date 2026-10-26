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

    /**
     * TorBox hands out signed URLs on regional CDN hostnames such as
     * `store-018.weur.tb-cdn.st`. The allowlist is a suffix list, so it has to cover the
     * CDN *domains* the provider actually uses — keeping it too narrow makes every
     * transfer fail with "Refusing to download from non-allowlisted host" (found by
     * running a real download, not by reading the code).
     */
    private val TORBOX = listOf(
        "dl.torbox.app", "cdn.torbox.app", "torbox.app", "media.torbox.app",
        "tb-cdn.io", "tb-cdn.cx", "tb-cdn.st", "tb-cdn.com", "torbox.dev",
    )
    private val REALDEBRID = listOf("real-debrid.com", "rd.nu", "rdb.so", "rd-cdn.com", "debrid.link")

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
