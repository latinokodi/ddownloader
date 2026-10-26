"use strict";
const electron = require("electron");
const api = {
  getSettings: () => electron.ipcRenderer.invoke("get-settings"),
  setSettings: (settings) => electron.ipcRenderer.invoke("set-settings", settings),
  searchMetaSearch: (query, tabId) => electron.ipcRenderer.invoke("search-metasearch", query, tabId ?? null),
  getDownloads: () => electron.ipcRenderer.invoke("get-downloads"),
  getDownloadedEpisodes: (title, season) => electron.ipcRenderer.invoke("get-downloaded-episodes", title, season),
  getSeasonCache: (imdbId, season) => electron.ipcRenderer.invoke("get-season-cache", imdbId, season),
  saveSeasonCache: (imdbId, season, episodes) => electron.ipcRenderer.invoke("save-season-cache", imdbId, season, episodes),
  addMagnet: (magnet, service, type, season, episode) => electron.ipcRenderer.invoke("add-magnet", magnet, service || "torbox", type || "", season ?? null, episode ?? null),
  addTorrentUrl: (url, service, type, season, episode) => electron.ipcRenderer.invoke("add-torrent-url", url, service || "torbox", type || "", season ?? null, episode ?? null),
  torboxHasHash: (hash) => electron.ipcRenderer.invoke("torbox-has-hash", hash),
  controlTorrent: (torrentId, operation) => electron.ipcRenderer.invoke("control-torrent", torrentId, operation),
  cancelDownload: (torrentId) => electron.ipcRenderer.invoke("cancel-download", torrentId),
  selectFolder: () => electron.ipcRenderer.invoke("select-folder"),
  authStart: () => electron.ipcRenderer.invoke("auth-start"),
  authPoll: (deviceCode) => electron.ipcRenderer.invoke("auth-poll", deviceCode),
  getUserInfo: () => electron.ipcRenderer.invoke("torbox-user-info"),
  testMetaSearch: () => electron.ipcRenderer.invoke("test-metasearch"),
  // ── Real-Debrid ──
  rdAuthStart: () => electron.ipcRenderer.invoke("rd-auth-start"),
  rdAuthPoll: (deviceCode) => electron.ipcRenderer.invoke("rd-auth-poll", deviceCode),
  rdUserInfo: () => electron.ipcRenderer.invoke("rd-user-info"),
  rdTraffic: () => electron.ipcRenderer.invoke("rd-traffic"),
  rdSelectFiles: (torrentId) => electron.ipcRenderer.invoke("rd-select-files", torrentId),
  onDownloadsUpdated: (callback) => {
    electron.ipcRenderer.on("downloads-updated", callback);
    return () => electron.ipcRenderer.removeAllListeners("downloads-updated");
  },
  onLog: (callback) => {
    electron.ipcRenderer.on("app-log", (_e, entry) => callback(entry));
    return () => electron.ipcRenderer.removeAllListeners("app-log");
  },
  getLogs: () => electron.ipcRenderer.invoke("get-logs"),
  getVersion: () => electron.ipcRenderer.invoke("get-app-version"),
  openFolder: (folderPath) => electron.ipcRenderer.invoke("open-folder", folderPath),
  clearCompleted: () => electron.ipcRenderer.invoke("clear-completed"),
  onFlareSolverrReady: (callback) => {
    electron.ipcRenderer.on("flaresolverr-ready", callback);
    return () => electron.ipcRenderer.removeAllListeners("flaresolverr-ready");
  },
  flaresolverrRestart: () => electron.ipcRenderer.invoke("flaresolverr-restart"),
  flaresolverrStatus: () => electron.ipcRenderer.invoke("flaresolverr-status"),
  onSearchProgress: (callback) => {
    electron.ipcRenderer.on("search-progress", (_e, progress) => callback(progress));
    return () => electron.ipcRenderer.removeAllListeners("search-progress");
  },
  onSearchError: (callback) => {
    electron.ipcRenderer.on("search-error", (_e, payload) => callback(payload));
    return () => electron.ipcRenderer.removeAllListeners("search-error");
  },
  checkPlugins: () => electron.ipcRenderer.invoke("check-plugins"),
  updatePlugins: () => electron.ipcRenderer.invoke("update-plugins"),
  // ── TMDB Discover ──
  tmdbLists: () => electron.ipcRenderer.invoke("tmdb-lists"),
  tmdbDetail: (tmdbId, mediaType) => electron.ipcRenderer.invoke("tmdb-detail", tmdbId, mediaType),
  tmdbSeason: (tmdbId, seasonNumber) => electron.ipcRenderer.invoke("tmdb-season", tmdbId, seasonNumber),
  tmdbSearch: (query) => electron.ipcRenderer.invoke("tmdb-search", query),
  tmdbValidate: (apiKey) => electron.ipcRenderer.invoke("tmdb-validate", apiKey),
  latinoSearch: (imdbId, mediaType, season, episode) => electron.ipcRenderer.invoke("latino-search", imdbId, mediaType, season, episode),
  latinoSearchBatch: (imdbId, mediaType, season, episode) => electron.ipcRenderer.invoke("latino-search-batch", imdbId, mediaType, season, episode),
  searchBatch: (query) => electron.ipcRenderer.invoke("search-batch", query),
  onLatinoSearchProgress: (callback) => {
    electron.ipcRenderer.on("latino-search-progress", (_e, progress) => callback(progress));
    return () => electron.ipcRenderer.removeAllListeners("latino-search-progress");
  },
  // ── Stremio Catalog ──
  catalogManifest: () => electron.ipcRenderer.invoke("catalog-manifest"),
  catalogItems: (type, id) => electron.ipcRenderer.invoke("catalog-items", type, id),
  catalogMeta: (type, imdbId) => electron.ipcRenderer.invoke("catalog-meta", type, imdbId),
  // ── Auto-update ──
  checkForUpdates: () => electron.ipcRenderer.invoke("check-for-updates"),
  downloadUpdate: () => electron.ipcRenderer.invoke("download-update"),
  installUpdate: () => electron.ipcRenderer.invoke("install-update"),
  dismissUpdate: () => electron.ipcRenderer.invoke("dismiss-update"),
  onUpdateAvailable: (callback) => {
    electron.ipcRenderer.on("update-available", (_e, version) => callback(version));
    return () => electron.ipcRenderer.removeAllListeners("update-available");
  },
  onUpdateNotAvailable: (callback) => {
    electron.ipcRenderer.on("update-not-available", callback);
    return () => electron.ipcRenderer.removeAllListeners("update-not-available");
  },
  onUpdateDownloadProgress: (callback) => {
    electron.ipcRenderer.on("update-download-progress", (_e, percent) => callback(percent));
    return () => electron.ipcRenderer.removeAllListeners("update-download-progress");
  },
  onUpdateDownloaded: (callback) => {
    electron.ipcRenderer.on("update-downloaded", callback);
    return () => electron.ipcRenderer.removeAllListeners("update-downloaded");
  },
  onUpdateError: (callback) => {
    electron.ipcRenderer.on("update-error", (_e, message) => callback(message));
    return () => electron.ipcRenderer.removeAllListeners("update-error");
  }
};
electron.contextBridge.exposeInMainWorld("electronAPI", api);
