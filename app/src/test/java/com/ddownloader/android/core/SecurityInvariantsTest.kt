package com.ddownloader.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ddownloader.android.download.HostAllowlist

/**
 * Guards the two "leak-free" invariants that are pure functions and therefore
 * cheap to pin down in CI:
 *  • the log buffer must never contain a credential, URL fragment or magnet hash;
 *  • the debrid CDN allowlist must accept only the service's domains (and their
 *    subdomains), which is what stops a hijacked redirect from exfiltrating a
 *    signed download URL.
 */
class SecurityInvariantsTest {

    @Test
    fun `tokens and api keys are redacted`() {
        val raw = "GET https://api.torbox.app/v1/api/torrents?token=abc123&bypass_cache=true"
        val out = AppLog.redact(raw)
        assertFalse("token leaked: $out", out.contains("abc123"))
        assertTrue(out.contains("token=***"))
    }

    @Test
    fun `bearer headers are redacted`() {
        val out = AppLog.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertFalse(out, out.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `magnets and long hex hashes are redacted`() {
        val magnet = "magnet:?xt=urn:btih:84729aecfccd94c19d117e91df59bb2edca6dfdb&dn=x"
        val out = AppLog.redact("adding $magnet")
        assertFalse("hash leaked: $out", out.contains("84729aecfccd94c19d117e91df59bb2edca6dfdb"))
        assertFalse(out, out.contains("magnet:?xt="))
    }

    @Test
    fun `url fragments are redacted`() {
        val out = AppLog.redact("GET https://cdn.example/file.mkv#sig=SECRETSIGNATURE")
        assertFalse(out, out.contains("SECRETSIGNATURE"))
    }

    @Test
    fun `allowlist accepts only the service domains and their subdomains`() {
        // torbox
        assertTrue(HostAllowlist.isAllowed("torbox", "dl.torbox.app"))
        assertTrue(HostAllowlist.isAllowed("torbox", "edge.cdn.torbox.app"))
        assertTrue(HostAllowlist.isAllowed("torbox", "tb-cdn.io"))
        assertFalse(HostAllowlist.isAllowed("torbox", "evil.example.com"))
        assertFalse("suffix confusion must not pass", HostAllowlist.isAllowed("torbox", "nottorbox.app"))
        assertFalse(HostAllowlist.isAllowed("torbox", "torbox.app.evil.com"))
        // real-debrid
        assertTrue(HostAllowlist.isAllowed("realdebrid", "download.real-debrid.com"))
        assertTrue(HostAllowlist.isAllowed("realdebrid", "rd.nu"))
        assertFalse(HostAllowlist.isAllowed("realdebrid", "dl.torbox.app"))
    }

    @Test
    fun `loopback is always allowed for local fixtures`() {
        assertTrue(HostAllowlist.isAllowed("torbox", "127.0.0.1"))
        assertTrue(HostAllowlist.isAllowed("realdebrid", "localhost"))
    }

    @Test
    fun `log buffer is bounded and records levels`() {
        AppLog.clear()
        repeat(620) { AppLog.i("test", "line $it") }
        assertEquals(500, AppLog.entries.value.size)
        assertTrue(AppLog.entries.value.last().text.contains("line 619"))
        AppLog.clear()
        assertEquals(0, AppLog.entries.value.size)
    }
}
