/**
 * flaresolverr.ts — Auto-downloading FlareSolverr process manager.
 *
 * On first run, downloads the latest FlareSolverr Windows release,
 * extracts it to the userData directory, and spawns it.
 * Subsequent runs reuse the cached installation.
 *
 * No Docker, no manual setup. Fully self-contained after first launch.
 */
import { spawn, execFile } from 'child_process';
import path from 'path';
import { app } from 'electron';
import http from 'http';
import https from 'https';
import fs from 'fs';
const FLARESOLVERR_PORT = 8191;
const HEALTH_CHECK_INTERVAL = 30000;
const STARTUP_TIMEOUT = 60000; // longer to account for possible download
const MAX_RESTART_COUNT = 3;
const FLARESOLVERR_API = 'https://api.github.com/repos/FlareSolverr/FlareSolverr/releases/latest';
let _proc = null;
let _healthy = false;
let _restartCount = 0;
let _downloadPromise = null;
function getFlareSolverrDir() {
    // Dev: use local electron/flaresolverr/
    if (process.env.VITE_DEV_SERVER_URL) {
        return path.join(__dirname, '..', 'electron', 'flaresolverr');
    }
    // Prod: store in userData so it persists across updates
    return path.join(app.getPath('userData'), 'flaresolverr');
}
function getFlareSolverrExe() {
    return path.join(getFlareSolverrDir(), 'flaresolverr', 'flaresolverr.exe');
}
// ── Download & extract ────────────────────────────────
function httpsGet(url) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers: { 'User-Agent': 'TorDownloader-PRO' } }, (res) => {
            if (res.statusCode === 302 || res.statusCode === 301) {
                httpsGet(res.headers.location).then(resolve).catch(reject);
                return;
            }
            if (res.statusCode !== 200) {
                reject(new Error(`HTTP ${res.statusCode}`));
                return;
            }
            let data = '';
            res.on('data', (chunk) => { data += chunk.toString(); });
            res.on('end', () => resolve(data));
        }).on('error', reject);
    });
}
function downloadFile(url, dest) {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        https.get(url, { headers: { 'User-Agent': 'TorDownloader-PRO' } }, (res) => {
            if (res.statusCode === 302 || res.statusCode === 301) {
                file.close();
                fs.unlinkSync(dest);
                downloadFile(res.headers.location, dest).then(resolve).catch(reject);
                return;
            }
            res.pipe(file);
            file.on('finish', () => { file.close(); resolve(); });
        }).on('error', (err) => {
            file.close();
            try {
                fs.unlinkSync(dest);
            }
            catch (_) { }
            reject(err);
        });
    });
}
async function downloadFlareSolverr() {
    const destDir = getFlareSolverrDir();
    console.log('[FlareSolverr] Downloading latest release...');
    // 1) Get latest release info
    const releaseJson = await httpsGet(FLARESOLVERR_API);
    const release = JSON.parse(releaseJson);
    const asset = release.assets?.find((a) => a.name.includes('windows_x64'));
    if (!asset)
        throw new Error('No Windows asset found in latest FlareSolverr release');
    console.log(`[FlareSolverr] Found: ${asset.name} (${(asset.size / 1024 / 1024).toFixed(0)} MB)`);
    // 2) Download
    const zipPath = path.join(destDir, 'flaresolverr.zip');
    fs.mkdirSync(destDir, { recursive: true });
    await downloadFile(asset.browser_download_url, zipPath);
    // 3) Extract via PowerShell
    console.log('[FlareSolverr] Extracting...');
    await new Promise((resolve, reject) => {
        execFile('powershell', [
            '-NoProfile', '-Command',
            `Expand-Archive -Path '${zipPath}' -DestinationPath '${destDir}' -Force`
        ], (err) => {
            if (err)
                reject(err);
            else
                resolve();
        });
    });
    // 4) Clean up zip
    try {
        fs.unlinkSync(zipPath);
    }
    catch (_) { }
    console.log('[FlareSolverr] Installation complete');
}
async function ensureFlareSolverr() {
    const exePath = getFlareSolverrExe();
    if (fs.existsSync(exePath))
        return;
    // Prevent concurrent downloads
    if (!_downloadPromise) {
        _downloadPromise = downloadFlareSolverr().catch((err) => {
            _downloadPromise = null;
            throw err;
        });
    }
    try {
        await _downloadPromise;
    }
    catch (err) {
        console.error('[FlareSolverr] Download failed:', err.message);
        throw err;
    }
}
// ── Process management ─────────────────────────────────
function healthCheck() {
    return new Promise((resolve) => {
        const req = http.get(`http://localhost:${FLARESOLVERR_PORT}/health`, (res) => {
            resolve(res.statusCode === 200);
        });
        req.on('error', () => resolve(false));
        req.setTimeout(5000, () => {
            req.destroy();
            resolve(false);
        });
    });
}
async function waitForReady(timeoutMs) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        const ok = await healthCheck();
        if (ok)
            return true;
        await new Promise(r => setTimeout(r, 1000));
    }
    return false;
}
function startProcess() {
    if (_proc)
        return;
    const exePath = getFlareSolverrExe();
    if (!fs.existsSync(exePath))
        return;
    console.log(`[FlareSolverr] Starting: ${exePath}`);
    _proc = spawn(exePath, ['--port', String(FLARESOLVERR_PORT)], {
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    _proc.stdout?.on('data', (_data) => {
        // suppress verbose FlareSolverr output
    });
    _proc.stderr?.on('data', (data) => {
        console.warn('[FlareSolverr]', data.toString().trim());
    });
    _proc.on('close', (code) => {
        console.warn(`[FlareSolverr] Process exited (code ${code})`);
        _proc = null;
        _healthy = false;
        _restartCount++;
        if (_restartCount <= MAX_RESTART_COUNT) {
            setTimeout(() => {
                if (!_proc)
                    startProcess();
            }, 5000);
        }
        else {
            console.error(`[FlareSolverr] Max restart attempts (${MAX_RESTART_COUNT}) reached. Giving up.`);
        }
    });
    _proc.on('error', (err) => {
        console.error('[FlareSolverr] Failed to start:', err.message);
        _proc = null;
    });
}
// ── Public API ─────────────────────────────────────────
export async function startFlareSolverr() {
    try {
        await ensureFlareSolverr();
    }
    catch (err) {
        console.warn('[FlareSolverr] Could not install — Cloudflare bypass unavailable:', err.message);
        return;
    }
    startProcess();
    if (!_proc)
        return;
    const ready = await waitForReady(STARTUP_TIMEOUT);
    if (ready) {
        _healthy = true;
        console.log('[FlareSolverr] Ready on port', FLARESOLVERR_PORT);
    }
    else {
        console.warn('[FlareSolverr] Startup timed out — will retry');
    }
    // Periodic health checks
    setInterval(async () => {
        const ok = await healthCheck();
        _healthy = ok;
        if (!ok && !_proc && _restartCount <= MAX_RESTART_COUNT) {
            console.warn('[FlareSolverr] Not running — restarting...');
            startProcess();
        }
    }, HEALTH_CHECK_INTERVAL);
}
export function getFlareSolverrUrl() {
    return `http://localhost:${FLARESOLVERR_PORT}`;
}
export function isFlareSolverrReady() {
    return _healthy;
}
export function getFlareSolverrStatus() {
    if (_healthy)
        return 'ready';
    if (_proc)
        return 'starting'; // process running but not yet healthy
    if (_restartCount > MAX_RESTART_COUNT)
        return 'failed';
    // Check if the exe exists — if not, it was never installed
    const exePath = getFlareSolverrExe();
    if (!fs.existsSync(exePath))
        return 'off';
    return 'starting'; // exe exists but not running — will auto-restart
}
export function stopFlareSolverr() {
    if (_proc) {
        console.log('[FlareSolverr] Stopping...');
        _proc.kill('SIGTERM');
        _proc = null;
        _healthy = false;
    }
}
export async function restartFlareSolverr() {
    console.log('[FlareSolverr] Manual restart requested');
    // Kill current process
    if (_proc) {
        _proc.kill('SIGTERM');
        _proc = null;
    }
    _healthy = false;
    _restartCount = 0; // reset — manual restart is intentional
    try {
        await ensureFlareSolverr();
    }
    catch (err) {
        console.error('[FlareSolverr] Cannot install:', err.message);
        return false;
    }
    startProcess();
    if (!_proc) {
        console.error('[FlareSolverr] Process failed to start');
        return false;
    }
    const ready = await waitForReady(STARTUP_TIMEOUT);
    if (ready) {
        _healthy = true;
        console.log('[FlareSolverr] Restarted successfully on port', FLARESOLVERR_PORT);
        return true;
    }
    console.warn('[FlareSolverr] Restart timed out');
    return false;
}
