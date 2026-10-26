package com.ddownloader.android.data

import android.app.Application
import com.ddownloader.android.api.CometApi
import com.ddownloader.android.api.RealDebridApi
import com.ddownloader.android.api.TmdbApi
import com.ddownloader.android.api.TorBoxApi
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.data.db.DownloadRecord
import com.ddownloader.android.data.db.DownloadsDb
import com.ddownloader.android.download.DownloadManager
import com.ddownloader.android.download.LinkResolver
import com.ddownloader.android.download.TitleResolver
import com.ddownloader.android.storage.MediaLayout

/**
 * Manual dependency graph (no Hilt/Koin — the app is small and this keeps the
 * release build free of annotation processors and runtime reflection).
 */
object ServiceLocator {

    private lateinit var application: Application

    lateinit var settings: SettingsRepository
        private set

    lateinit var db: DownloadsDb
        private set

    lateinit var manager: DownloadManager
        private set

    lateinit var torbox: TorBoxApi
        private set

    lateinit var realdebrid: RealDebridApi
        private set

    lateinit var comet: CometApi
        private set

    lateinit var tmdb: TmdbApi
        private set

    lateinit var accounts: AccountAuth
        private set

    lateinit var deviceAuth: DeviceAuth
        private set

    lateinit var updateChecker: UpdateChecker
        private set

    lateinit var updateInstaller: UpdateInstaller
        private set

    fun init(app: Application) {
        application = app
        settings = SettingsRepository(app)
        db = DownloadsDb(app)
        db.refreshCache()
        torbox = TorBoxApi(settings)
        realdebrid = RealDebridApi(settings)
        comet = CometApi()
        tmdb = TmdbApi(settings)
        accounts = AccountAuth(settings, torbox, realdebrid)
        deviceAuth = DeviceAuth(settings)
        updateChecker = UpdateChecker()
        updateInstaller = UpdateInstaller(app)
        manager = DownloadManager(app, settings, db).apply {
            linkResolver = LinkResolver { record -> resolveLink(record) }
            titleResolver = TitleResolver { name, kind ->
                val cfg = settings.current()
                if (cfg.hasTmdb) {
                    runCatching {
                        tmdb.resolveTitle(
                            MediaLayout.parseReleaseName(name).title,
                            if (kind == MediaLayout.Kind.SERIES) "series" else "movie",
                        )
                    }.getOrNull()
                } else {
                    null
                }
            }
        }
        // Resume transfers that died with the network (verified by the lifecycle test).
        com.ddownloader.android.download.NetworkMonitor.start(app)
        // Nothing should keep a partial alive once its queue row is gone.
        runCatching { com.ddownloader.android.storage.Storage.pruneOrphans(app, db.records.value.map { it.id }) }
        AppLog.i("DI", "initialised (db=${db.records.value.size} records)")
    }

    /**
     * Fresh direct URL for a queued transfer. Debrid links are short-lived, so
     * this runs at the start of every attempt (including resumes after death).
     */
    private suspend fun resolveLink(record: DownloadRecord): String {
        return when (record.service) {
            "torbox" -> {
                val torrentId = record.remoteId ?: error("missing torrent id")
                val fileId = record.fileId ?: error("missing file id")
                torbox.requestDownloadLink(torrentId, fileId).getOrThrow()
            }
            "realdebrid" -> {
                val link = record.url.ifBlank { error("missing RD link") }
                realdebrid.unrestrict(link).getOrThrow().download
            }
            else -> record.url
        }
    }

    fun appContext(): android.content.Context = application
}
