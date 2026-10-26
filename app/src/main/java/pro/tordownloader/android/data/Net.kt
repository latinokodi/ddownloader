package pro.tordownloader.android.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import pro.tordownloader.android.core.AppLog
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The single HTTP chokepoint for the app.
 *
 * This client talks to the debrid APIs (TorBox, Real-Debrid), TMDB and the
 * embedded Comet add-on over **HTTPS only**. "tor" in this project refers to
 * TorBox, not the Tor network — there is no proxy, no onion routing and no
 * anonymity layer here (see DECISIONS.md, Decision A).
 *
 * What is still enforced — because "leak-free" applies to credentials and data:
 *  • HTTPS for every non-loopback origin. The manifest network-security-config
 *    already forbids cleartext; [HttpsOnly] is the second, code-level guard so a
 *    future config change cannot silently downgrade a request.
 *  • Manual redirect handling with a per-call host allowlist, so a debrid CDN
 *    cannot bounce a signed URL to an attacker-controlled origin. (The Electron
 *    original validated only the first URL while axios followed redirects.)
 *  • Redirect cap, bounded timeouts, no credential-bearing headers on retries.
 *
 * Loopback stays plain-HTTP-capable on purpose: it is used only for local test
 * fixtures (release traffic never targets loopback).
 */
object Net {

    fun isLoopback(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase().trim('[', ']')
        return h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1"
    }

    /**
     * The emulator reaches the host machine at 10.0.2.2. Verification fixtures run
     * there over plain HTTP, so debug builds may talk to it; release builds may not.
     */
    fun isDebugFixtureHost(host: String?): Boolean =
        (pro.tordownloader.android.BuildConfig.DEBUG || pro.tordownloader.android.BuildConfig.VERIFY_HARNESS) &&
            host == "10.0.2.2"

    private val HttpsOnly = Interceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps && !isLoopback(url.host) && !isDebugFixtureHost(url.host)) {
            throw IOException("Refusing cleartext request to ${url.host} — HTTPS is required")
        }
        chain.proceed(chain.request())
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpsOnly)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Streaming client for transfers. A FINITE read timeout is essential: with
     * readTimeout(0) a connection that silently stops delivering (wifi/data toggled
     * off, NAT blackhole) blocks the read forever, so the engine never fails, never
     * retries, and the transfer freezes mid-file — reproduced by the lifecycle
     * "network toggle" scenario. 30 s of no bytes is unambiguous; the retry then
     * resumes from the bytes already on disk.
     */
    val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Short-timeout client for health probes. */
    val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    data class HttpResult(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val headers: Map<String, String>,
    )

    /**
     * Manual redirect following with per-hop host validation. [allowedHost] is
     * consulted for every hop; a redirect off the allowlist aborts the request.
     */
    fun follow(
        request: Request,
        maxHops: Int = 5,
        client: OkHttpClient = this.client,
        allowedHost: (String) -> Boolean,
    ): Response {
        var req = request
        var hops = 0
        while (true) {
            val resp = client.newCall(req).execute()
            if (resp.code !in intArrayOf(301, 302, 303, 307, 308)) return resp
            val location = resp.header("Location") ?: return resp
            resp.close()
            if (hops++ >= maxHops) throw IOException("Too many redirects")
            val next = req.url.resolve(location) ?: throw IOException("Bad redirect: $location")
            if (!allowedHost(next.host)) {
                throw IOException("Refusing redirect to non-allowlisted host ${next.host}")
            }
            AppLog.d("Net", "redirect ${req.url.host} → ${next.host} (${resp.code})")
            req = req.newBuilder().url(next).build()
        }
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = this.client,
        allowedHost: (String) -> Boolean = { true },
    ): HttpResult {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        follow(builder.build(), client = client, allowedHost = allowedHost).use { resp ->
            val body = resp.body?.string().orEmpty()
            val hdrs = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            return HttpResult(resp.code, body, resp.request.url.toString(), hdrs)
        }
    }
}
