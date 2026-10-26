import { BrowserWindow } from 'electron';
/** Cancel an in-progress local download by its TorBox ID. Returns true if something was aborted. */
export declare function cancelLocalDownload(tid: string): boolean;
export declare function startWorker(mainWindow: BrowserWindow | null): void;
