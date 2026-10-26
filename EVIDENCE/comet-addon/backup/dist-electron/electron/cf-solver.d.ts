/**
 * cf-solver.ts — Uses Electron's Chromium to solve Cloudflare challenges
 * and extract cookies for Python plugins to reuse.
 *
 * For heavy-CF sites like 1337x, cloudscraper alone isn't enough.
 * We use a hidden BrowserWindow to load the site, wait for the
 * "Just a moment..." challenge to clear, then export cookies
 * to a temp file that the Python runner can inject.
 */
interface Cookie {
    name: string;
    value: string;
    domain: string;
    path: string;
    secure: boolean;
    httpOnly: boolean;
    sameSite: string;
}
/** Pre-solve CF for a list of domains. Runs on app startup. */
export declare function preSolveDomains(domains: string[]): Promise<void>;
/** Get the latest cookie file path for the Python runner */
export declare function getCookieFilePath(): string;
/** Export cookies for a specific domain (for CLI use) */
export declare function getCookiesForDomain(domain: string): Cookie[];
export {};
