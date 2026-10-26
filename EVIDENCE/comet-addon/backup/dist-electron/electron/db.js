import Database from 'better-sqlite3';
import path from 'path';
import { app } from 'electron';
const DB_PATH = path.join(app.getPath('userData'), 'tordownloader.db');
let db;
export function initDB() {
    db = new Database(DB_PATH);
    db.pragma('journal_mode = WAL');
    db.exec(`
    CREATE TABLE IF NOT EXISTS settings (
      id INTEGER PRIMARY KEY,
      torbox_token VARCHAR DEFAULT '',
      realdebrid_token VARCHAR DEFAULT '',
      realdebrid_refresh_token VARCHAR DEFAULT '',
      realdebrid_client_id VARCHAR DEFAULT '',
      realdebrid_client_secret VARCHAR DEFAULT '',
      destination_folder VARCHAR DEFAULT '',
      movies_folder VARCHAR DEFAULT '',
      series_folder VARCHAR DEFAULT '',
      auto_remove_completed BOOLEAN DEFAULT 0,
      tmdb_api_key VARCHAR DEFAULT '',
      last_update_prompt VARCHAR DEFAULT ''
    );

    CREATE TABLE IF NOT EXISTS downloads (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      torbox_id VARCHAR UNIQUE,
      name VARCHAR DEFAULT 'Pending...',
      status VARCHAR DEFAULT 'downloading',
      progress INTEGER DEFAULT 0,
      local_status VARCHAR DEFAULT 'pending',
      local_progress INTEGER DEFAULT 0,
      local_speed INTEGER DEFAULT 0,
      local_eta VARCHAR DEFAULT '',
      local_path VARCHAR,
      service VARCHAR DEFAULT 'torbox',
      type VARCHAR DEFAULT '',
      season INTEGER,
      episode INTEGER,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );
  `);
    // Migration: add columns that may not exist in older schema
    const migrations = [
        `ALTER TABLE downloads ADD COLUMN local_eta VARCHAR DEFAULT ''`,
        `ALTER TABLE downloads ADD COLUMN service VARCHAR DEFAULT 'torbox'`,
        `ALTER TABLE settings ADD COLUMN realdebrid_token VARCHAR DEFAULT ''`,
        `ALTER TABLE settings ADD COLUMN realdebrid_refresh_token VARCHAR DEFAULT ''`,
        `ALTER TABLE settings ADD COLUMN realdebrid_client_id VARCHAR DEFAULT ''`,
        `ALTER TABLE settings ADD COLUMN realdebrid_client_secret VARCHAR DEFAULT ''`,
        `ALTER TABLE settings ADD COLUMN tmdb_api_key VARCHAR DEFAULT ''`,
        `ALTER TABLE settings ADD COLUMN last_update_prompt VARCHAR DEFAULT ''`,
        `ALTER TABLE settings ADD COLUMN movies_folder VARCHAR DEFAULT ''`,
        `ALTER TABLE settings ADD COLUMN series_folder VARCHAR DEFAULT ''`,
        `ALTER TABLE downloads ADD COLUMN type VARCHAR DEFAULT ''`,
        `ALTER TABLE downloads ADD COLUMN season INTEGER`,
        `ALTER TABLE downloads ADD COLUMN episode INTEGER`,
    ];
    for (const sql of migrations) {
        try {
            db.exec(sql);
        }
        catch (_) { /* Column already exists */ }
    }
    // Create a default settings row if not exists
    const count = db.prepare('SELECT COUNT(*) as c FROM settings').get();
    if (count.c === 0) {
        db.prepare('INSERT INTO settings (id) VALUES (1)').run();
    }
}
export function getSettings() {
    const stmt = db.prepare('SELECT * FROM settings WHERE id = 1');
    const row = stmt.get();
    return {
        ...row,
        auto_remove_completed: Boolean(row.auto_remove_completed)
    };
}
export function updateSettings(settings) {
    const fields = [];
    const values = [];
    for (const [key, value] of Object.entries(settings)) {
        if (key === 'id')
            continue;
        fields.push(`${key} = ?`);
        values.push(typeof value === 'boolean' ? (value ? 1 : 0) : value);
    }
    if (fields.length > 0) {
        const query = `UPDATE settings SET ${fields.join(', ')} WHERE id = 1`;
        db.prepare(query).run(...values);
    }
}
export function getDownloads() {
    return db.prepare('SELECT * FROM downloads ORDER BY created_at DESC').all();
}
export function getDownloadByTorboxId(torboxId) {
    return db.prepare('SELECT * FROM downloads WHERE torbox_id = ?').get(torboxId);
}
export function addDownload(data) {
    const keys = Object.keys(data).join(', ');
    const placeholders = Object.keys(data).map(() => '?').join(', ');
    const values = Object.values(data);
    db.prepare(`INSERT OR IGNORE INTO downloads (${keys}) VALUES (${placeholders})`).run(...values);
}
export function updateDownload(torboxId, data) {
    const fields = [];
    const values = [];
    for (const [key, value] of Object.entries(data)) {
        if (key === 'id' || key === 'torbox_id')
            continue;
        fields.push(`${key} = ?`);
        values.push(value);
    }
    if (fields.length > 0) {
        const query = `UPDATE downloads SET ${fields.join(', ')} WHERE torbox_id = ?`;
        values.push(torboxId);
        db.prepare(query).run(...values);
    }
}
export function deleteDownload(torboxId) {
    db.prepare('DELETE FROM downloads WHERE torbox_id = ?').run(torboxId);
}
export function getDB() {
    return db;
}
