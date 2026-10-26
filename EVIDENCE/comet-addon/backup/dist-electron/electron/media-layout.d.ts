export interface ParsedRelease {
    title: string;
    year?: number;
    season?: number;
}
export declare function parseReleaseName(name: string): ParsedRelease;
export declare function tmdbResolve(parsed: ParsedRelease, type: 'movie' | 'series', apiKey: string): Promise<{
    title: string;
    year?: number;
} | null>;
/**
 * Compute the per-download destination root + relative folder for a release.
 * movie  -> <movies_root>/<Title (Year)>/
 * series -> <series_root>/<Title (Year)>/Season NN/
 * Falls back to the legacy `destination_folder` when the per-type root is unset.
 *
 * `seasonOverride` (the season stored in the DB when the transfer was added)
 * wins over the season parsed from the torrent name, so every episode of a
 * season lands in the same "Season NN" folder even when release names vary
 * (e.g. "T1E1", "Temporada 1", or no season marker at all).
 */
export declare function computeDestination(settings: {
    destination_folder: string;
    movies_folder: string;
    series_folder: string;
    tmdb_api_key: string;
}, torrentName: string, type: 'movie' | 'series' | '', seasonOverride?: number | null): Promise<{
    root: string;
    folder: string;
    season: number | undefined;
}>;
/** Sanitize a path segment for filesystem use (safe chars only). */
export declare function safeSegment(value: string): string;
