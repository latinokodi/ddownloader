import { type Database as DatabaseType } from 'better-sqlite3';
export declare function initDB(): void;
export type DebridService = 'torbox' | 'realdebrid';
export interface Settings {
    id: number;
    torbox_token: string;
    realdebrid_token: string;
    realdebrid_refresh_token: string;
    realdebrid_client_id: string;
    realdebrid_client_secret: string;
    destination_folder: string;
    movies_folder: string;
    series_folder: string;
    auto_remove_completed: boolean;
    tmdb_api_key: string;
    last_update_prompt: string;
}
export interface Download {
    id: number;
    torbox_id: string;
    name: string;
    status: string;
    progress: number;
    local_status: string;
    local_progress: number;
    local_speed: number;
    local_eta: string;
    local_path: string | null;
    service: DebridService;
    type: 'movie' | 'series' | '';
    season: number | null;
    episode: number | null;
    created_at: string;
}
export declare function getSettings(): Settings;
export declare function updateSettings(settings: Partial<Settings>): void;
export declare function getDownloads(): Download[];
export declare function getDownloadByTorboxId(torboxId: string): Download | undefined;
export declare function addDownload(data: Partial<Download>): void;
export declare function updateDownload(torboxId: string, data: Partial<Download>): void;
export declare function deleteDownload(torboxId: string): void;
export declare function getDB(): DatabaseType;
