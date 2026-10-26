package com.ddownloader.android.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.ddownloader.android.core.AppLog
import java.io.File
import java.security.MessageDigest

/**
 * Downloads and installs a release APK from the project's GitHub Releases.
 *
 * Trust chain (the part that makes a self-updater safe):
 *  1. HTTPS only — every hop validated by [Net].
 *  2. If the release publishes `<apk>.sha256`, the downloaded bytes must match it.
 *  3. **Signature equality** — the downloaded APK must be signed by the same key as
 *     the installed app. This is what prevents the updater from ever installing a
 *     different application (or a build signed by someone else) even if the release
 *     feed were tampered with.
 *  4. Android requires the update to be signed with the same key *anyway*; this check
 *     turns that silent failure into a clear, early error message.
 *  5. The APK is handed to the system package installer through a FileProvider — the
 *     app never tries to install silently (that would need root or a device-owner).
 *
 * Settings are preserved by the OS: an in-place update with the same applicationId
 * and signing key keeps `/data/data/<pkg>` (DataStore settings, SQLite queue, the
 * Keystore-wrapped tokens) intact. The updater never clears app data.
 */
class UpdateInstaller(private val context: Context) {

    sealed interface DownloadResult {
        data class Ready(val apk: File, val sha256: String) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    fun updatesDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    suspend fun download(
        release: UpdateChecker.AvailableRelease,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val target = File(updatesDir(), "update-${release.version}.apk")
        if (target.exists() && target.length() > 0) {
            AppLog.i(TAG, "reusing cached update ${target.name} (${target.length()} bytes)")
            return@withContext verifyAndFinish(target, release)
        }
        val partial = File(updatesDir(), "${target.name}.part")
        partial.delete()
        try {
            val req = Request.Builder().url(release.apkUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "DDownloader-Android")
                .build()
            // GitHub release assets redirect to objects.githubusercontent.com /
            // release-assets.githubusercontent.com — all validated per hop.
            val allowed = { host: String ->
                host == "github.com" || host.endsWith(".github.com") ||
                    host == "objects.githubusercontent.com" ||
                    host.endsWith(".githubusercontent.com")
            }
            Net.follow(req, client = Net.downloadClient, allowedHost = allowed).use { resp ->
                if (resp.code !in 200..299) return@withContext DownloadResult.Failed("HTTP ${resp.code} for the release asset")
                val body = resp.body ?: return@withContext DownloadResult.Failed("empty response")
                val total = if (release.sizeBytes > 0) release.sizeBytes else body.contentLength()
                var written = 0L
                var lastTick = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 250) {
                                lastTick = now
                                onProgress(written, total)
                            }
                        }
                        out.flush()
                    }
                }
                onProgress(written, total)
                if (total > 0 && written != total) {
                    return@withContext DownloadResult.Failed("truncated download: $written of $total bytes")
                }
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext DownloadResult.Failed("could not finalise the downloaded APK")
            }
            AppLog.i(TAG, "downloaded ${target.name} (${target.length()} bytes)")
            verifyAndFinish(target, release)
        } catch (t: Throwable) {
            AppLog.e(TAG, "update download failed: ${t.message}", t)
            DownloadResult.Failed(t.message ?: "download failed")
        }
    }

    private suspend fun verifyAndFinish(
        apk: File,
        release: UpdateChecker.AvailableRelease,
    ): DownloadResult {
        val digest = sha256(apk)
        // 1) optional published checksum
        if (!release.sha256Url.isNullOrBlank()) {
            val published = fetchPublishedSha(release.sha256Url)
            if (published != null && !published.equals(digest, ignoreCase = true)) {
                apk.delete()
                return DownloadResult.Failed("checksum mismatch (expected ${published.take(12)}…, got ${digest.take(12)}…)")
            }
        }
        // 2) signer must equal the installed app's signer
        return when (val signer = verifierCheck(apk)) {
            SignerCheck.Match -> DownloadResult.Ready(apk, digest)
            SignerCheck.Mismatch -> {
                apk.delete()
                DownloadResult.Failed("update is signed by a different key than the installed app — refused")
            }
            is SignerCheck.Unknown -> DownloadResult.Failed("could not verify the APK signature (${signer.reason})")
        }
    }

    private fun fetchPublishedSha(url: String): String? = try {
        val res = Net.get(url, client = Net.probeClient, allowedHost = { true })
        if (res.code in 200..299) res.body.trim().substringBefore(' ').takeIf { it.length >= 32 } else null
    } catch (t: Throwable) {
        AppLog.w(TAG, "could not fetch published checksum: ${t.message}")
        null
    }

    sealed interface SignerCheck {
        data object Match : SignerCheck
        data object Mismatch : SignerCheck
        data class Unknown(val reason: String) : SignerCheck
    }

    fun verifierCheck(apk: File): SignerCheck {
        return try {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
                ?: return SignerCheck.Unknown("not a readable APK")
            if (archive.packageName != context.packageName) {
                return SignerCheck.Mismatch
            }
            val incoming = signers(archive.signingInfo?.apkContentsSigners)
            val installed = signers(
                pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners,
            )
            when {
                incoming.isEmpty() || installed.isEmpty() -> SignerCheck.Unknown("no signing certificates")
                incoming.any { inc -> installed.any { it.contentEquals(inc) } } -> SignerCheck.Match
                else -> SignerCheck.Mismatch
            }
        } catch (t: Throwable) {
            SignerCheck.Unknown(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun signers(sigs: Array<Signature>?): List<ByteArray> =
        sigs?.map { it.toByteArray() } ?: emptyList()

    /** Hands the verified APK to the system installer. The user confirms. */
    fun install(apk: File): Result<Unit> {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return Result.failure(IllegalStateException("no package installer available on this device"))
            }
            context.startActivity(intent)
            AppLog.i(TAG, "handed ${apk.name} to the system installer")
            Result.success(Unit)
        } catch (t: Throwable) {
            AppLog.e(TAG, "could not start the installer: ${t.message}", t)
            Result.failure(t)
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Removes stale downloaded updates (keeps the one being offered). */
    fun prune(keep: File?) {
        updatesDir().listFiles()?.forEach { f ->
            if (keep == null || f.name != keep.name) runCatching { f.delete() }
        }
    }

    companion object {
        private const val TAG = "Update"
    }
}
