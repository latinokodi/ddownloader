/**
 * plugin-updater.ts — Fetches and updates search plugins from the
 * qBittorrent community repository.
 *
 * Downloads .py plugin files from GitHub, compares versions, and
 * saves updated plugins to a user-writable directory.
 */
export interface PluginInfo {
    name: string;
    localVersion: string | null;
    remoteVersion: string | null;
    needsUpdate: boolean;
}
export interface UpdateResult {
    plugins: PluginInfo[];
    updated: string[];
    errors: string[];
}
export declare function checkPluginsForUpdates(): Promise<PluginInfo[]>;
export declare function updatePlugins(): Promise<UpdateResult>;
