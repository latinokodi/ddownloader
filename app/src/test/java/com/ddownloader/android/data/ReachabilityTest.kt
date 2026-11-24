package com.ddownloader.android.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that made account linking survive a flaky network.
 *
 * The reported bug: authorizing in the browser worked, the app then polled
 * `api.torbox.app` once, the phone failed to resolve the host, and the pairing was abandoned
 * with "Could not link TorBox — Unable to resolve host". A resolver failure is not a verdict on
 * the code, so [Reachability.isUnreachable] must classify it as retryable — while a provider
 * *rejection* (HTTP 4xx surfaced as an ordinary exception) must not be retried forever.
 */
class ReachabilityTest {

    @Test
    fun `dns failure is unreachable`() {
        // The exact exception OkHttp throws when the resolver cannot find the host.
        val dns = UnknownHostException(
            "Unable to resolve host \"api.torbox.app\": No address associated with hostname",
        )
        assertTrue(Reachability.isUnreachable(dns))
    }

    @Test
    fun `wrapped dns failure is still unreachable`() {
        // OkHttp's InterruptedIOException / chain wrappers hide the interesting cause one level down.
        val wrapped = IOException("network error", UnknownHostException("api.torbox.app"))
        assertTrue(Reachability.isUnreachable(wrapped))
    }

    @Test
    fun `timeouts and refused connections are unreachable`() {
        assertTrue(Reachability.isUnreachable(SocketTimeoutException("timeout")))
        assertTrue(Reachability.isUnreachable(ConnectException("ECONNREFUSED")))
    }

    @Test
    fun `a provider rejection is not unreachable`() {
        // What pollTorBox raises after parsing a definitive answer from the API.
        assertFalse(Reachability.isUnreachable(IllegalStateException("TorBox returned ITEM_NOT_FOUND")))
        assertFalse(Reachability.isUnreachable(IllegalStateException("HTTP 403")))
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        // Defensive: a cause cycle must not hang the retry decision. A RuntimeException is not
        // a transport failure, so the answer is false — the point is that it *answers*.
        val loop = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(Reachability.isUnreachable(loop))

        // The same cycle on an IOException is still a transport failure, and still terminates.
        val ioLoop = object : IOException("io loop") {
            override val cause: Throwable get() = this
        }
        assertTrue(Reachability.isUnreachable(ioLoop))
    }
}
