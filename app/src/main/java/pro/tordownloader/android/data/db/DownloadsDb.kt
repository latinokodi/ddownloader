package pro.tordownloader.android.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class TransferState {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = this == QUEUED || this == RESOLVING || this == DOWNLOADING || this == PAUSED
}

data class DownloadRecord(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val name: String,
    val expectedSize: Long = 0L,
    val bytesDone: Long = 0L,
    val state: TransferState = TransferState.QUEUED,
    val error: String? = null,
    val service: String = "direct",           // torbox | realdebrid | direct
    val mediaType: String = "",               // movie | series | "" (parity with worker.resolveDestination)
    val season: Int? = null,
    val episode: Int? = null,
    val infoHash: String? = null,
    val partialPath: String = "",
    val finalUri: String? = null,
    val destDisplay: String = "",
    val etag: String? = null,
    val lastModified: String? = null,
    // Debrid provenance: lets a failed/expired link be re-resolved instead of
    // restarting the whole transfer.
    val remoteId: String? = null,
    val fileId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progressPercent: Int
        get() = if (expectedSize <= 0) 0 else ((bytesDone.toDouble() / expectedSize) * 100).toInt().coerceIn(0, 100)
}

/**
 * Hand-written SQLite layer (no Room / no annotation processor) for the transfer
 * queue. Mirrors the Electron app's `downloads` table but normalises the
 * free-form `local_status` strings the original used ("Downloading 2/3 (47%)")
 * into an explicit [TransferState] enum + numeric progress, which is what makes
 * crash-safe resume possible: the partial file length is the source of truth and
 * [bytesDone] is only a hint for the UI.
 */
class DownloadsDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    companion object {
        private const val NAME = "downloads.db"
        private const val VERSION = 1
        const val TABLE = "downloads"
    }

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
              id TEXT PRIMARY KEY NOT NULL,
              url TEXT NOT NULL,
              name TEXT NOT NULL,
              expected_size INTEGER NOT NULL DEFAULT 0,
              bytes_done INTEGER NOT NULL DEFAULT 0,
              state TEXT NOT NULL,
              error TEXT,
              service TEXT NOT NULL DEFAULT 'direct',
              media_type TEXT NOT NULL DEFAULT '',
              season INTEGER,
              episode INTEGER,
              info_hash TEXT,
              partial_path TEXT NOT NULL DEFAULT '',
              final_uri TEXT,
              dest_display TEXT NOT NULL DEFAULT '',
              etag TEXT,
              last_modified TEXT,
              remote_id TEXT,
              file_id TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_downloads_state ON $TABLE(state)")
        db.execSQL("CREATE INDEX idx_downloads_updated ON $TABLE(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 — no migrations yet. Destructive upgrade is acceptable pre-release
        // because the queue can be rebuilt from the services; partial files stay.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun refreshCache() {
        _records.value = queryAll()
    }

    fun queryAll(): List<DownloadRecord> = readableDatabase.query(
        TABLE, null, null, null, null, null, "created_at DESC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toRecord()) } }

    fun byId(id: String): DownloadRecord? = readableDatabase.query(
        TABLE, null, "id = ?", arrayOf(id), null, null, null
    ).use { c -> if (c.moveToFirst()) c.toRecord() else null }

    fun upsert(record: DownloadRecord) {
        val cv = ContentValues().apply {
            put("id", record.id)
            put("url", record.url)
            put("name", record.name)
            put("expected_size", record.expectedSize)
            put("bytes_done", record.bytesDone)
            put("state", record.state.name)
            put("error", record.error)
            put("service", record.service)
            put("media_type", record.mediaType)
            put("season", record.season)
            put("episode", record.episode)
            put("info_hash", record.infoHash)
            put("partial_path", record.partialPath)
            put("final_uri", record.finalUri)
            put("dest_display", record.destDisplay)
            put("etag", record.etag)
            put("last_modified", record.lastModified)
            put("remote_id", record.remoteId)
            put("file_id", record.fileId)
            put("created_at", record.createdAt)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        refreshCache()
    }

    fun updateProgress(id: String, bytes: Long, state: TransferState) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET bytes_done = ?, state = ?, updated_at = ? WHERE id = ?",
            arrayOf(bytes, state.name, System.currentTimeMillis(), id),
        )
        refreshCache()
    }

    fun markState(id: String, state: TransferState, error: String? = null) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET state = ?, error = ?, updated_at = ? WHERE id = ?",
            arrayOf(state.name, error, System.currentTimeMillis(), id),
        )
        refreshCache()
    }

    fun delete(id: String) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id))
        refreshCache()
    }

    fun deleteByState(vararg states: TransferState) {
        val placeholders = states.joinToString(",") { "?" }
        writableDatabase.delete(TABLE, "state IN ($placeholders)", states.map { it.name }.toTypedArray())
        refreshCache()
    }

    /** Records that were mid-flight when the process died. */
    fun interrupted(): List<DownloadRecord> = readableDatabase.query(
        TABLE, null, "state IN (?,?,?)",
        arrayOf(TransferState.QUEUED.name, TransferState.RESOLVING.name, TransferState.DOWNLOADING.name),
        null, null, "created_at ASC",
    ).use { c -> buildList { while (c.moveToNext()) add(c.toRecord()) } }

    private fun Cursor.toRecord(): DownloadRecord = DownloadRecord(
        id = getString(getColumnIndexOrThrow("id")),
        url = getString(getColumnIndexOrThrow("url")),
        name = getString(getColumnIndexOrThrow("name")),
        expectedSize = getLong(getColumnIndexOrThrow("expected_size")),
        bytesDone = getLong(getColumnIndexOrThrow("bytes_done")),
        state = runCatching { TransferState.valueOf(getString(getColumnIndexOrThrow("state"))) }
            .getOrDefault(TransferState.FAILED),
        error = getStringOrNull("error"),
        service = getString(getColumnIndexOrThrow("service")),
        mediaType = getString(getColumnIndexOrThrow("media_type")),
        season = getIntOrNull("season"),
        episode = getIntOrNull("episode"),
        infoHash = getStringOrNull("info_hash"),
        partialPath = getString(getColumnIndexOrThrow("partial_path")),
        finalUri = getStringOrNull("final_uri"),
        destDisplay = getString(getColumnIndexOrThrow("dest_display")),
        etag = getStringOrNull("etag"),
        lastModified = getStringOrNull("last_modified"),
        remoteId = getStringOrNull("remote_id"),
        fileId = getStringOrNull("file_id"),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    private fun Cursor.getStringOrNull(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.getIntOrNull(col: String): Int? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getInt(i)
    }
}
