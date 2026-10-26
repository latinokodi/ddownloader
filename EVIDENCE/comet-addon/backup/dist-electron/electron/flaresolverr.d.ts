/**
 * flaresolverr.ts — Auto-downloading FlareSolverr process manager.
 *
 * On first run, downloads the latest FlareSolverr Windows release,
 * extracts it to the userData directory, and spawns it.
 * Subsequent runs reuse the cached installation.
 *
 * No Docker, no manual setup. Fully self-contained after first launch.
 */
export declare function startFlareSolverr(): Promise<void>;
export declare function getFlareSolverrUrl(): string;
export declare function isFlareSolverrReady(): boolean;
export declare function getFlareSolverrStatus(): 'ready' | 'starting' | 'failed' | 'off';
export declare function stopFlareSolverr(): void;
export declare function restartFlareSolverr(): Promise<boolean>;
