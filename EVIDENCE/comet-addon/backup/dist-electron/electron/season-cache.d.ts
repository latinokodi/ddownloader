interface SeasonCacheEntry {
    ts: number;
    episodes: Record<string, unknown[]>;
}
/** Cached per-episode results for a series season, or null. */
export declare function getSeasonCache(imdbId: string, season: number): SeasonCacheEntry | null;
/** Save resolved per-episode results for a series season (bounded). */
export declare function saveSeasonCache(imdbId: string, season: number, episodes: Record<string, unknown[]>): void;
export {};
