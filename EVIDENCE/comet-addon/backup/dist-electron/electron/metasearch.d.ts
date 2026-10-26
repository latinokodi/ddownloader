/**
 * metasearch.ts — Native multi-engine torrent search using qBittorrent plugins.
 *
 * Replaces Jackett + FlareSolverr Docker stack with a self-contained solution:
 * spawns a Python runner that loads qBittorrent search plugins, aggregates
 * results across engines, deduplicates, and returns JSON.
 *
 * No external services, no Docker, no separate installs.
 * Only dependency: Python 3 with `cloudscraper` (auto-installed if missing).
 */
export interface MetaResult {
    title: string;
    size: string;
    seeders: number;
    peers: number;
    link: string;
    indexer: string;
    info_hash: string | null;
}
export interface SearchProgress {
    type: 'engine_start' | 'engine_results' | 'done';
    engine?: string;
    results?: MetaResult[];
    total?: number;
}
export interface SearchStreamCallbacks {
    onProgress: (progress: SearchProgress) => void;
    onDone: (results: MetaResult[]) => void;
    onError: (error: Error) => void;
}
export declare class MetaSearch {
    private runnerPath;
    private ready;
    constructor();
    initialize(): Promise<void>;
    search(query: string): Promise<MetaResult[]>;
    searchStream(query: string, callbacks: SearchStreamCallbacks): Promise<void>;
}
export declare function getMetaSearch(): MetaSearch;
export declare function initMetaSearch(): void;
