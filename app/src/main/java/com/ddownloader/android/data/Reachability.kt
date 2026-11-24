package com.ddownloader.android.data

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Decides whether a throwable means "this phone could not reach the provider" rather than
 * "the provider said no".
 *
 * The distinction is the whole point of the account-linking retry: `Unable to resolve host
 * "api.torbox.app"` is a DNS/transport problem that says nothing about whether the device code
 * the user is typing into the browser is still valid. Treating it as a final rejection ended a
 * perfectly recoverable pairing and surfaced the raw resolver message to the user.
 *
 * Pure and Android-free on purpose, so it can be unit-tested on the JVM without a Context.
 * `UnknownHostException` is the one that actually produced the reported failure; the rest are
 * the same class of problem (no route, timeout, TLS handshake) and are handled identically.
 */
internal object Reachability {

    fun isUnreachable(t: Throwable): Boolean {
        var cursor: Throwable? = t
        while (cursor != null) {
            if (isTransportFailure(cursor)) return true
            // OkHttp wraps resolver failures in an IOException whose cause is the real reason,
            // so a bare `is IOException` check must not short-circuit the walk.
            val next = cursor.cause
            cursor = if (next === cursor) null else next
        }
        // Any other IOException is still a transport-level failure, not a provider verdict.
        return t is IOException
    }

    private fun isTransportFailure(t: Throwable): Boolean = when (t) {
        is UnknownHostException -> true
        is SocketTimeoutException -> true
        is ConnectException -> true
        is NoRouteToHostException -> true
        is PortUnreachableException -> true
        is SSLException -> true
        else -> false
    }
}
