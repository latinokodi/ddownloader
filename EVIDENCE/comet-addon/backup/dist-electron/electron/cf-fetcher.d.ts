/**
 * cf-fetcher.ts — Local HTTP bridge for Python plugins to fetch CF-protected
 * pages through Electron's native Chromium engine.
 *
 * Starts a tiny HTTP server on localhost that accepts POST /fetch requests
 * with a JSON body { "url": "..." }. Uses a hidden BrowserWindow to load
 * the URL, waits for Cloudflare challenges to clear, and returns the page HTML.
 */
export declare function startCFServer(): number;
export declare function getCFServerPort(): number;
export declare function stopCFServer(): void;
