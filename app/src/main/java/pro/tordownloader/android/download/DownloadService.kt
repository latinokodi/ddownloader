package pro.tordownloader.android.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pro.tordownloader.android.R
import pro.tordownloader.android.core.AppLog
import pro.tordownloader.android.core.Fmt
import pro.tordownloader.android.data.ServiceLocator
import pro.tordownloader.android.data.db.TransferState
import pro.tordownloader.android.ui.MainActivity

/**
 * Foreground service that hosts the transfer queue.
 *
 * Why a foreground service: the process must stay alive for a multi-hour
 * download that the user started. Type = `dataSync` — the correct type for user-initiated
 * file transfer — which on Android 15+ comes with a 6-hour budget and a call to
 * [onTimeout]; that is handled by pausing cleanly (state is already durable, so
 * the next start resumes) instead of being force-killed mid-write.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ServiceLocator.manager.onActiveChanged = {
            scope.launch { refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                ServiceLocator.manager.activeIds().forEach { ServiceLocator.manager.pause(it) }
            }
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> {
                scope.launch {
                    val resumed = ServiceLocator.manager.resumeInterrupted()
                    AppLog.i(TAG, "service started (resumed=$resumed)")
                }
            }
        }
        observe()
        refreshNotification()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException or missing
            // notification permission on a weird OEM build — never crash.
            AppLog.e(TAG, "startForeground failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun observe() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            ServiceLocator.manager.live.collectLatest {
                refreshNotification()
            }
        }
    }

    private fun refreshNotification() {
        val live = ServiceLocator.manager.live.value.values
        val active = live.filter { it.state == TransferState.DOWNLOADING || it.state == TransferState.RESOLVING }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildNotification(active)
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
        if (ServiceLocator.manager.activeIds().isEmpty()) {
            // Nothing left to do → leave the foreground so the notification goes away.
            stopSelf()
        }
    }

    private fun buildNotification(
        active: Collection<DownloadManager.Live> = emptyList(),
    ): Notification {
        val first = active.firstOrNull()
        val animated = active.size > 1 || (first != null && first.state == TransferState.DOWNLOADING)
        val builder = NotificationCompat.Builder(this, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(
                when {
                    active.isEmpty() -> "TorDownloader ready"
                    active.size == 1 -> first!!.name
                    else -> "${active.size} downloads"
                }
            )
            .setContentText(
                when {
                    active.isEmpty() -> "Tor active — waiting for transfers"
                    first == null -> ""
                    else -> buildString {
                        append(Fmt.bytes(first.bytes))
                        if (first.total > 0) append(" / ${Fmt.bytes(first.total)}")
                        if (first.bytesPerSecond > 0) append(" • ${Fmt.speed(first.bytesPerSecond)}")
                        if (first.eta.isNotBlank() && first.eta != "—") append(" • ETA ${first.eta}")
                    }
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Pause all", actionIntent(ACTION_PAUSE_ALL))
            .addAction(0, "Stop", actionIntent(ACTION_STOP))

        if (first != null && first.total > 0) {
            builder.setProgress(100, first.percent, false)
        } else if (animated) {
            builder.setProgress(0, 0, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Top-level Tor/downloads status in the shade.
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("Tor is carrying all traffic"))
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, DownloadService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Transfer progress and Tor status"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TOR, "Tor", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Transfer notifications"
                setShowBadge(false)
            }
        )
    }

    /**
     * Android 15 (API 35): a dataSync foreground service gets 6 hours before the
     * system calls this. Pause cleanly; the DB + partial files make the next
     * start resume exactly where it stopped.
     */
    override fun onTimeout(startId: Int) {
        AppLog.w(TAG, "foreground dataSync budget exhausted — pausing transfers")
        ServiceLocator.manager.activeIds().forEach { ServiceLocator.manager.pause(it) }
        stopEverything()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(TAG, "foreground timeout for type $fgsType — pausing transfers")
        ServiceLocator.manager.activeIds().forEach { ServiceLocator.manager.pause(it) }
        stopEverything()
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not silently kill an in-flight transfer.
        AppLog.i(TAG, "task removed — keeping foreground service alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.i(TAG, "service destroyed")
        ServiceLocator.manager.onActiveChanged = null
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_DOWNLOADS = "downloads"
        private const val CHANNEL_TOR = "tor"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "pro.tordownloader.android.START"
        const val ACTION_PAUSE_ALL = "pro.tordownloader.android.PAUSE_ALL"
        const val ACTION_STOP = "pro.tordownloader.android.STOP"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
