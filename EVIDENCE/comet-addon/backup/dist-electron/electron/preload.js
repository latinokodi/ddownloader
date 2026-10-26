import { contextBridge, ipcRenderer } from 'electron';
const api = {
    getSettings: () => ipcRenderer.invoke('get-settings'),
    setSettings: (settings) => ipcRenderer.invoke('set-settings', settings),
    searchMetaSearch: (query, tabId) => ipcRenderer.invoke('search-metasearch', query, tabId ?? null),
    getDownloads: () => ipcRenderer.invoke('get-downloads'),
    getDownloadedEpisodes: (title, season) => ipcRenderer.invoke('get-downloaded-episodes', title, season),
    getSeasonCache: (imdbId, season) => ipcRenderer.invoke('get-season-cache', imdbId, season),
    saveSeasonCache: (imdbId, season, episodes) => ipcRenderer.invoke('save-season-cache', imdbId, season, episodes),
    addMagnet: (magnet, service, type, season, episode) => ipcRenderer.invoke('add-magnet', magnet, service || 'torbox', type || '', season ?? null, episode ?? null),
    addTorrentUrl: (url, service, type, season, episode) => ipcRenderer.invoke('add-torrent-url', url, service || 'torbox', type || '', season ?? null, episode ?? null),
    torboxHasHash: (hash) => ipcRenderer.invoke('torbox-has-hash', hash),
    controlTorrent: (torrentId, operation) => ipcRenderer.invoke('control-torrent', torrentId, operation),
    cancelDownload: (torrentId) => ipcRenderer.invoke('cancel-download', torrentId),
    selectFolder: () => ipcRenderer.invoke('select-folder'),
    authStart: () => ipcRenderer.invoke('auth-start'),
    authPoll: (deviceCode) => ipcRenderer.invoke('auth-poll', deviceCode),
    getUserInfo: () => ipcRenderer.invoke('torbox-user-info'),
    testMetaSearch: () => ipcRenderer.invoke('test-metasearch'),
    // ── Real-Debrid ──
    rdAuthStart: () => ipcRenderer.invoke('rd-auth-start'),
    rdAuthPoll: (deviceCode) => ipcRenderer.invoke('rd-auth-poll', deviceCode),
    rdUserInfo: () => ipcRenderer.invoke('rd-user-info'),
    rdTraffic: () => ipcRenderer.invoke('rd-traffic'),
    rdSelectFiles: (torrentId) => ipcRenderer.invoke('rd-select-files', torrentId),
    onDownloadsUpdated: (callback) => {
        ipcRenderer.on('downloads-updated', callback);
        return () => ipcRenderer.removeAllListeners('downloads-updated');
    },
    onLog: (callback) => {
        ipcRenderer.on('app-log', (_e, entry) => callback(entry));
        return () => ipcRenderer.removeAllListeners('app-log');
    },
    getLogs: () => ipcRenderer.invoke('get-logs'),
    getVersion: () => ipcRenderer.invoke('get-app-version'),
    openFolder: (folderPath) => ipcRenderer.invoke('open-folder', folderPath),
    clearCompleted: () => ipcRenderer.invoke('clear-completed'),
    onFlareSolverrReady: (callback) => {
        ipcRenderer.on('flaresolverr-ready', callback);
        return () => ipcRenderer.removeAllListeners('flaresolverr-ready');
    },
    flaresolverrRestart: () => ipcRenderer.invoke('flaresolverr-restart'),
    flaresolverrStatus: () => ipcRenderer.invoke('flaresolverr-status'),
    onSearchProgress: (callback) => {
        ipcRenderer.on('search-progress', (_e, progress) => callback(progress));
        return () => ipcRenderer.removeAllListeners('search-progress');
    },
    onSearchError: (callback) => {
        ipcRenderer.on('search-error', (_e, payload) => callback(payload));
        return () => ipcRenderer.removeAllListeners('search-error');
    },
    checkPlugins: () => ipcRenderer.invoke('check-plugins'),
    updatePlugins: () => ipcRenderer.invoke('update-plugins'),
    // ── TMDB Discover ──
    tmdbLists: () => ipcRenderer.invoke('tmdb-lists'),
    tmdbDetail: (tmdbId, mediaType) => ipcRenderer.invoke('tmdb-detail', tmdbId, mediaType),
    tmdbSeason: (tmdbId, seasonNumber) => ipcRenderer.invoke('tmdb-season', tmdbId, seasonNumber),
    tmdbSearch: (query) => ipcRenderer.invoke('tmdb-search', query),
    tmdbValidate: (apiKey) => ipcRenderer.invoke('tmdb-validate', apiKey),
    latinoSearch: (imdbId, mediaType, season, episode) => ipcRenderer.invoke('latino-search', imdbId, mediaType, season, episode),
    latinoSearchBatch: (imdbId, mediaType, season, episode) => ipcRenderer.invoke('latino-search-batch', imdbId, mediaType, season, episode),
    searchBatch: (query) => ipcRenderer.invoke('search-batch', query),
    onLatinoSearchProgress: (callback) => {
        ipcRenderer.on('latino-search-progress', (_e, progress) => callback(progress));
        return () => ipcRenderer.removeAllListeners('latino-search-progress');
    },
    // ── Stremio Catalog ──
    catalogManifest: () => ipcRenderer.invoke('catalog-manifest'),
    catalogItems: (type, id) => ipcRenderer.invoke('catalog-items', type, id),
    catalogMeta: (type, imdbId) => ipcRenderer.invoke('catalog-meta', type, imdbId),
    // ── Auto-update ──
    checkForUpdates: () => ipcRenderer.invoke('check-for-updates'),
    downloadUpdate: () => ipcRenderer.invoke('download-update'),
    installUpdate: () => ipcRenderer.invoke('install-update'),
    dismissUpdate: () => ipcRenderer.invoke('dismiss-update'),
    onUpdateAvailable: (callback) => {
        ipcRenderer.on('update-available', (_e, version) => callback(version));
        return () => ipcRenderer.removeAllListeners('update-available');
    },
    onUpdateNotAvailable: (callback) => {
        ipcRenderer.on('update-not-available', callback);
        return () => ipcRenderer.removeAllListeners('update-not-available');
    },
    onUpdateDownloadProgress: (callback) => {
        ipcRenderer.on('update-download-progress', (_e, percent) => callback(percent));
        return () => ipcRenderer.removeAllListeners('update-download-progress');
    },
    onUpdateDownloaded: (callback) => {
        ipcRenderer.on('update-downloaded', callback);
        return () => ipcRenderer.removeAllListeners('update-downloaded');
    },
    onUpdateError: (callback) => {
        ipcRenderer.on('update-error', (_e, message) => callback(message));
        return () => ipcRenderer.removeAllListeners('update-error');
    },
};
contextBridge.exposeInMainWorld('electronAPI', api);
