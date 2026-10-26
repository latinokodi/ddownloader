declare function isDebridCDN(url: string): boolean;
export { isDebridCDN };
export interface DownloadProgress {
    bytes_done: number;
    progress_percent: number;
    speed: number;
    total_bytes: number;
}
export interface DownloadResult {
    success: boolean;
    error?: string;
    bytes_downloaded: number;
    /** True when a resume was requested but the server ignored the Range header (200). */
    rangeIgnored?: boolean;
}
export declare class Downloader {
    private url;
    private destPath;
    private expectedSize;
    private cancelSource;
    constructor(url: string, destPath: string, expectedSize?: number);
    /** Abort an in-progress download. Safe to call at any time. */
    abort(): void;
    start(onProgress?: (p: DownloadProgress) => void): Promise<DownloadResult>;
}
