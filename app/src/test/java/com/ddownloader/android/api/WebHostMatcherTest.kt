package com.ddownloader.android.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the redirect allowlist that protects credential-bearing requests.
 *
 * `Web.execute` used to call `Net.follow(req, allowedHost = { true })` for every verb, so a
 * 302 returned by (or injected into a response from) any POST would have carried the
 * `Authorization: Bearer` header to whatever host the Location pointed at. The GET path had a
 * matcher; POST/PUT/DELETE did not. These tests lock the matcher shut.
 */
class WebHostMatcherTest {

    private val torbox = listOf("api.torbox.app")

    @Test
    fun `the provider host and its subdomains are allowed`() {
        val match = Web.hostMatcher(torbox)
        assertTrue(match("api.torbox.app"))
        assertTrue("case-insensitive", match("API.TorBox.App"))
        assertTrue(match("edge.api.torbox.app"))
    }

    @Test
    fun `foreign hosts are rejected even with a plausible name`() {
        val match = Web.hostMatcher(torbox)
        assertFalse(match("evil.example.com"))
        assertFalse("suffix confusion must not pass", match("nottorbox.app"))
        assertFalse(match("torbox.app.evil.com"))
        assertFalse(match("api.torbox.app.evil.com"))
    }

    @Test
    fun `an empty allowlist preserves the unrestricted behaviour for credential-free calls`() {
        // Debris APIs do not redirect, and calls without a token are allowed to go anywhere
        // (updates, catalogues). This documents that the change did not break them.
        val match = Web.hostMatcher(emptyList())
        assertTrue(match("api.github.com"))
        assertTrue(match("anything.example"))
    }

    @Test
    fun `real-debrid is matched the same way`() {
        val match = Web.hostMatcher(listOf("api.real-debrid.com"))
        assertTrue(match("api.real-debrid.com"))
        assertFalse(match("api.torbox.app"))
        assertFalse(match("real-debrid.com.evil.com"))
    }
}
