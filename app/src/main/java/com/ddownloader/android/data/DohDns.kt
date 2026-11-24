package com.ddownloader.android.data

import com.ddownloader.android.api.Web
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.core.LogL10n
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolver with a fallback, because the system one can be the thing that is broken.
 *
 * Reported failure: the phone authorized the code in the browser — the page even said "Code
 * accepted" — but the app answered `Unable to resolve host "api.torbox.app": No address
 * associated with hostname`, never collected the token, and the browser then showed
 * "Code expired". The browser was right and the app was wrong: Chrome resolves names through
 * its own DNS-over-HTTPS, so a broken **system** resolver (Private DNS pointed at an unreachable
 * DoT host, a DNS-filtering VPN, a router handing out a dead resolver) breaks the app while the
 * browser keeps working. Retrying alone cannot fix that; the lookup has to take a different path.
 *
 * Order of attempts:
 *  1. `Dns.SYSTEM` — the normal path. Everything behaves exactly as before when DNS works.
 *  2. DNS-over-HTTPS, addressed by IP literal (`https://1.1.1.1/dns-query`) so the fallback
 *     itself needs no DNS to bootstrap. Both endpoints present a certificate with an IP SAN
 *     for that literal, so this is still ordinary certificate-validated HTTPS — no pinning
 *     exception, no cleartext.
 *
 * Scope is deliberately narrow: the fallback only fires for the API hosts this app must reach,
 * never for arbitrary destinations. That keeps a user's network-level filtering choices intact
 * everywhere else and keeps the list auditable — the same reasoning as `HostAllowlist`.
 *
 * If both paths fail the original [UnknownHostException] is rethrown, so callers still see a
 * genuine "unreachable" and the account-linking UI can say so.
 */
internal object DohDns : Dns {

    private const val TAG = "Dns"
    private const val TTL_MS = 5 * 60 * 1000L

    /**
     * Hosts whose resolution may fall back to DoH. Suffix match, so `api.torbox.app` is covered
     * by `torbox.app` and `foo.tb-cdn.st` by `tb-cdn.st`.
     */
    private val FALLBACK_HOSTS = listOf(
        "torbox.app", "tb-cdn.io", "tb-cdn.cx", "tb-cdn.st", "tb-cdn.com", "torbox.dev",
        "real-debrid.com", "rd.nu", "rdb.so", "rd-cdn.com", "debrid.link",
        "themoviedb.org", "github.com", "githubusercontent.com", "stremio.ru",
    )

    private data class Cached(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** DoH endpoints by IP literal — no DNS needed to reach them. */
    private val endpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    /** Separate client: short timeouts, and pinned to [Dns.SYSTEM] so it can never recurse here. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { if (it.expiresAt > System.currentTimeMillis()) return it.addresses }

        val systemFailure: UnknownHostException = try {
            val system = Dns.SYSTEM.lookup(hostname)
            if (system.isNotEmpty()) return system
            UnknownHostException("No address associated with hostname: $hostname")
        } catch (e: UnknownHostException) {
            e
        } catch (e: IOException) {
            UnknownHostException("System resolver failed for $hostname: ${e.message}")
        }

        if (!eligible(hostname)) throw systemFailure

        val resolved = resolveOverHttps(hostname)
        if (resolved.isEmpty()) throw systemFailure

        cache[hostname] = Cached(resolved, System.currentTimeMillis() + TTL_MS)
        AppLog.w(
            TAG,
            LogL10n.of(
                "system resolver failed for $hostname; used DNS-over-HTTPS (${resolved.size} address(es))",
                "el resolutor del sistema falló para $hostname; se usó DNS-over-HTTPS (${resolved.size} dirección(es))",
            ),
        )
        return resolved
    }

    fun eligible(hostname: String): Boolean {
        val h = hostname.lowercase().trimEnd('.')
        return FALLBACK_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun resolveOverHttps(hostname: String): List<InetAddress> {
        // A records first: more likely to work on networks with broken IPv6, which is a common
        // companion of a broken resolver.
        for (type in listOf("A", "AAAA")) {
            val addresses = resolve(hostname, type)
            if (addresses.isNotEmpty()) return addresses
        }
        return emptyList()
    }

    private fun resolve(hostname: String, type: String): List<InetAddress> {
        for (base in endpoints) {
            val body = fetch(base, hostname, type) ?: continue
            val addresses = runCatching { parseAddresses(body) }.getOrNull()
            if (addresses == null) {
                AppLog.d(TAG, "DoH $base sent an unreadable answer for $hostname/$type")
                continue
            }
            if (addresses.isNotEmpty()) return addresses
        }
        return emptyList()
    }

    /** One DoH query, or null when this endpoint could not be used. Never throws. */
    private fun fetch(base: String, hostname: String, type: String): String? {
        val request = Request.Builder()
            .url("$base?name=$hostname&type=$type")
            .header("Accept", "application/dns-json")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.d(TAG, "DoH $base answered HTTP ${resp.code} for $hostname/$type")
                    null
                } else {
                    resp.body?.string()
                }
            }
        } catch (t: Throwable) {
            AppLog.d(TAG, "DoH $base unreachable for $hostname/$type: ${t.message}")
            null
        }
    }

    /**
     * Extracts the IP literals from a DNS-over-HTTPS JSON answer. The shape used by both
     * `1.1.1.1/dns-query` and `8.8.8.8/resolve` is
     * `{"Status":0,"Answer":[{"name":"…","type":1,"TTL":300,"data":"104.20.28.56"}]}`.
     *
     * `Status != 0` (NXDOMAIN / SERVFAIL) is treated as "no answer" rather than an error, so the
     * other endpoint still gets a turn. Only records whose `data` is a literal IP are accepted;
     * CNAME records (type 5) in the same answer list are skipped.
     */
    fun parseAddresses(body: String): List<InetAddress> {
        val root = Web.json.parseToJsonElement(body).jsonObject
        val status = runCatching { root["Status"]?.jsonPrimitive?.int }.getOrNull() ?: 0
        if (status != 0) return emptyList()

        val answers = runCatching { root["Answer"]?.jsonArray }.getOrNull() ?: return emptyList()
        return answers.mapNotNull { el ->
            val record = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val type = runCatching { record["type"]?.jsonPrimitive?.int }.getOrNull()
            if (type != 1 && type != 28) return@mapNotNull null
            val data = runCatching { record["data"]?.jsonPrimitive?.content }.getOrNull()
                ?: return@mapNotNull null
            // A literal only. Anything that is not an IP would trigger a fresh DNS lookup here,
            // which is exactly what just failed.
            if (!data.contains(':') && data.count { it == '.' } != 3) return@mapNotNull null
            runCatching { InetAddress.getByName(data) }.getOrNull()
        }
    }
}
