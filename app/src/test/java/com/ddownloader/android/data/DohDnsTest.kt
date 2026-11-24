package com.ddownloader.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DNS-over-HTTPS fallback that fixes the reported pairing failure.
 *
 * The observable symptom was `Unable to resolve host "api.torbox.app": No address associated
 * with hostname` in the app while the same phone's browser loaded the authorization page and
 * said "Code accepted" — the browser resolves through its own DoH, so a broken system resolver
 * only broke the app. These tests cover the two halves that can be checked without a network:
 * which hosts may use the fallback, and that a DoH JSON answer is parsed correctly.
 */
class DohDnsTest {

    @Test
    fun `api hosts are eligible for the fallback`() {
        assertTrue(DohDns.eligible("api.torbox.app"))
        assertTrue(DohDns.eligible("torbox.app"))
        assertTrue(DohDns.eligible("store-018.weur.tb-cdn.st"))
        assertTrue(DohDns.eligible("api.real-debrid.com"))
        assertTrue(DohDns.eligible("api.themoviedb.org"))
    }

    @Test
    fun `unrelated hosts never use the fallback`() {
        // A user's network filtering stays in force everywhere else.
        assertFalse(DohDns.eligible("example.com"))
        assertFalse(DohDns.eligible("ads.tracker.net"))
        assertFalse("suffix confusion must not pass", DohDns.eligible("nottorbox.app"))
        assertFalse(DohDns.eligible("torbox.app.evil.com"))
    }

    @Test
    fun `DoH answer yields literal addresses`() {
        val body = """
            {"Status":0,"TC":false,"RD":true,"RA":true,
             "Question":[{"name":"api.torbox.app.","type":1}],
             "Answer":[
               {"name":"api.torbox.app.","type":5,"TTL":300,"data":"torbox.app."},
               {"name":"api.torbox.app.","type":1,"TTL":300,"data":"104.20.28.56"},
               {"name":"api.torbox.app.","type":1,"TTL":300,"data":"172.66.170.114"}]}
        """.trimIndent()
        val addresses = DohDns.parseAddresses(body).map { it.hostAddress }
        // The CNAME is skipped; only the two A records survive.
        assertEquals(listOf("104.20.28.56", "172.66.170.114"), addresses)
    }

    @Test
    fun `NXDOMAIN is an empty answer, not an exception`() {
        // Status 3 = NXDOMAIN. The other DoH endpoint still gets a turn.
        assertEquals(emptyList<String>(), DohDns.parseAddresses("""{"Status":3}""").map { it.hostAddress })
    }

    @Test
    fun `an answer with no Address records is empty`() {
        assertEquals(emptyList<String>(), DohDns.parseAddresses("""{"Status":0}""").map { it.hostAddress })
    }
}
