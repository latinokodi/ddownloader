package pro.tordownloader.android.data

import android.app.Application
import pro.tordownloader.android.api.CometApi
import pro.tordownloader.android.api.RealDebridApi
import pro.tordownloader.android.api.TmdbApi
import pro.tordownloader.android.api.TorBoxApi
import pro.tordownloader.android.core.AppLog
import pro.tordownloader.android.data.db.DownloadRecord
import pro.tordownloader.android.data.db.DownloadsDb
import pro.tordownloader.android.download.DownloadManager
import pro.tordownloader.android.download.LinkResolver
import pro.tordownloader.android.download.TitleResolver
import pro.tordownloader.android.storage.MediaLayout

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

    fun init(app: Application) {
        application = app
        settings = SettingsRepository(app)
        db = DownloadsDb(app)
        db.refreshCache()
        torbox = TorBoxApi(settings)
        realdebrid = RealDebridApi(settings)
        comet = CometApi()
        tmdb = TmdbApi(settings)
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
        pro.tordownloader.android.download.NetworkMonitor.start(app)
        // Nothing should keep a partial alive once its queue row is gone.
        runCatching { pro.tordownloader.android.storage.Storage.pruneOrphans(app, db.records.value.map { it.id }) }
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
