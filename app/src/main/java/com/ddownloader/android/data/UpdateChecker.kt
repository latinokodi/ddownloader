package com.ddownloader.android.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ddownloader.android.BuildConfig
import com.ddownloader.android.api.Web
import com.ddownloader.android.api.str
import com.ddownloader.android.core.AppLog

/**
 * Self-update source: the project's own GitHub Releases.
 *
 * The app is distributed as an APK from
 * `https://github.com/<UPDATE_REPO>/releases`, so the release feed is the update
 * channel. This checker is deliberately conservative:
 *
 *  • unauthenticated GitHub API (60 req/h/IP) → results are throttled by the caller
 *  • picks the newest non-draft release (prefers a non-prerelease entry)
 *  • compares semver-ish versions numerically, ignoring a leading "v"
 *  • treats every failure mode distinctly (offline / rate limit / malformed / no APK)
 *    so the UI never claims "up to date" when it simply could not ask
 */
class UpdateChecker {

    data class AvailableRelease(
        val tag: String,
        val version: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val sha256Url: String?,
        val sizeBytes: Long,
        val publishedAt: String,
    )

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val release: AvailableRelease) : Result
        data class Failed(val reason: String, val rateLimited: Boolean = false) : Result
    }

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): Result =
        withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10"
        try {
            val res = Net.get(
                url = url,
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to "DDownloader-Android/$currentVersion",
                ),
                client = Net.probeClient,
                allowedHost = { it == "api.github.com" || it.endsWith(".github.com") },
            )
            when {
                res.code == 403 || res.code == 429 ->
                    Result.Failed("GitHub rate limit (HTTP ${res.code}) — try again later", rateLimited = true)
                res.code !in 200..299 ->
                    Result.Failed("GitHub returned HTTP ${res.code}")
                else -> {
                    val release = pickNewest(parseReleases(res.body), currentVersion)
                    if (release == null) Result.UpToDate else Result.Available(release)
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "update check failed: ${t.javaClass.simpleName}: ${t.message}")
            Result.Failed(t.message ?: "could not reach GitHub")
        }
    }

    internal fun parseReleases(body: String): List<AvailableRelease> {
        val root = runCatching { Web.json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        return root.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o["draft"]?.jsonPrimitive?.content == "true") return@mapNotNull null
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val assets = o["assets"]?.jsonArray ?: return@mapNotNull null
            val apk = assets.mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name")?.endsWith(".apk", true) == true }
                ?: return@mapNotNull null
            val sha = assets.mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name")?.endsWith(".sha256", true) == true }
            val apkName = apk.str("name").orEmpty()
            AvailableRelease(
                tag = tag,
                version = normalizeVersion(tag),
                name = o.str("name") ?: tag,
                notes = o.str("body").orEmpty(),
                apkUrl = apk.str("browser_download_url") ?: return@mapNotNull null,
                apkName = apkName,
                sha256Url = sha?.str("browser_download_url"),
                sizeBytes = apk.str("size")?.toLongOrNull() ?: 0L,
                publishedAt = o.str("published_at").orEmpty(),
                // prerelease flag is used only for preference, not exclusion
            )
        }
    }

    /** Newest release whose version is strictly greater than [currentVersion]. */
    internal fun pickNewest(releases: List<AvailableRelease>, currentVersion: String): AvailableRelease? =
        releases
            .filter { isNewer(it.version, currentVersion) }
            .maxByOrNull { key(it.version) }

    companion object {
        private const val TAG = "Update"

        fun normalizeVersion(v: String): String =
            v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')

        fun key(version: String): Long {
            val parts = normalizeVersion(version).split('.').mapNotNull { it.toIntOrNull() }.take(4)
            var key = 0L
            for (i in 0 until 4) {
                key = key * 1000 + (parts.getOrElse(i) { 0 }).coerceIn(0, 999)
            }
            return key
        }

        fun isNewer(candidate: String, current: String): Boolean = key(candidate) > key(current)
    }
}
