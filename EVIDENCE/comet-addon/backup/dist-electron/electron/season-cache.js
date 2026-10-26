// Season resolution cache (in-memory, session-only): stores per-episode search
// results for a (series imdbId + season) so re-opening the season download
// within the same app session doesn't re-run the latino providers / metasearch
// (which hit the streaming sites). The cache lives in the main process and is
// cleared automatically when the app closes or is reopened.
const cache = new Map();
const MAX_ENTRIES = 100;
/** Cached per-episode results for a series season, or null. */
export function getSeasonCache(imdbId, season) {
    const entry = cache.get(`${imdbId}:${season}`);
    if (!entry || typeof entry.episodes !== 'object' || entry.episodes === null)
        return null;
    return entry;
}
/** Save resolved per-episode results for a series season (bounded). */
export function saveSeasonCache(imdbId, season, episodes) {
    if (cache.size >= MAX_ENTRIES) {
        // Evict the oldest entry to keep memory bounded.
        let oldestKey = null;
        let oldestTs = Infinity;
        for (const [k, v] of cache) {
            if (v.ts < oldestTs) {
                oldestTs = v.ts;
                oldestKey = k;
            }
        }
        if (oldestKey)
            cache.delete(oldestKey);
    }
    cache.set(`${imdbId}:${season}`, { ts: Date.now(), episodes: episodes || {} });
}
