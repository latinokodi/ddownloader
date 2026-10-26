package com.ddownloader.android.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.data.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Re-arms interrupted transfers after process death / reboot.
 *
 * The queue is durable (SQLite + partial files), so this worker only has to
 * restart the engine. Android 12+ may refuse `startForegroundService` from a
 * background worker; that refusal is caught and logged — the transfer then runs
 * in-process and, if the OS kills us again, the next launch resumes anyway. No
 * download is ever restarted from zero because the offset is the file length.
 */
class ResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Both passes are cheap and idempotent: re-arm network-shaped failures first,
        // then anything left mid-flight from a previous process.
        val retried = ServiceLocator.manager.retryNetworkFailures()
        val interrupted = ServiceLocator.manager.resumeInterrupted()
        AppLog.i(TAG, "resume worker: retried=$retried interrupted=$interrupted")
        if (retried + interrupted > 0) {
            runCatching { DownloadService.start(applicationContext) }
                .onFailure { AppLog.w(TAG, "could not start foreground service from worker: ${it.message}") }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ResumeWorker"
        private const val UNIQUE = "td-resume-interrupted"
        private const val RETRY_UNIQUE = "td-retry-after-outage"

        /**
         * One-shot, network-constrained retry. This is the belt to
         * [NetworkMonitor]'s braces: if the connectivity callback is missed (OEM
         * quirk, callback registered after the transition), WorkManager still runs
         * the retry the moment a validated network exists.
         */
        fun scheduleRetrySoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<ResumeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RETRY_UNIQUE,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ResumeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
