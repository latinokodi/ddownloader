package pro.tordownloader.android.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import pro.tordownloader.android.core.AppLog
import pro.tordownloader.android.data.ServiceLocator

/**
 * Restarts transfers that died because the network went away.
 *
 * Without this, a download that hits a connectivity outage burns its three attempts
 * inside a few seconds and then sits in FAILED until the user taps Retry or the
 * 15-minute WorkManager pass runs — which is exactly what the lifecycle
 * "network toggle" scenario caught (partial file stuck at 1.3 MB after the network
 * came back). The fix is to observe connectivity and re-arm network-shaped failures
 * immediately, resuming from the bytes already on disk.
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"
    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: run {
            AppLog.w(TAG, "ConnectivityManager unavailable — outage retry disabled")
            return
        }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLog.i(TAG, "network available — re-arming interrupted transfers")
                    val retried = ServiceLocator.manager.retryNetworkFailures()
                    if (retried > 0) {
                        runCatching { DownloadService.start(context) }
                    }
                }
            })
            registered = true
            AppLog.i(TAG, "watching connectivity for interrupted transfers")
        }.onFailure { AppLog.w(TAG, "could not register network callback: ${it.message}") }
    }
}
