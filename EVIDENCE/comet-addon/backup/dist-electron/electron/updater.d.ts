import { BrowserWindow } from 'electron';
export declare function initAutoUpdater(win: BrowserWindow): void;
export declare function checkForUpdates(): void;
export declare function checkForUpdatesManual(): void;
export declare function downloadUpdate(): void;
export declare function installUpdate(): void;
export declare function dismissUpdate(): void;
export declare function isUpdateDownloaded(): boolean;
