import { execFile } from 'child_process';
import path from 'path';
const IDM_PATH = 'C:\\Program Files (x86)\\Internet Download Manager\\IDMan.exe';
export function sanitizeFolderName(name) {
    return name.replace(/[<>:"/\\|?*]/g, '_').replace(/\s+/g, '_').substring(0, 100) || 'Bunkr_Album';
}
export function extractAlbumName(url) {
    try {
        const u = new URL(url);
        const parts = u.pathname.split('/').filter(Boolean);
        // Bunkr URLs: /a/albumname or /albumname
        const last = parts[parts.length - 1];
        return sanitizeFolderName(last || 'album');
    }
    catch {
        return 'Bunkr_Album';
    }
}
export function sendToIDM(dlUrl, filename, downloadPath, folderName) {
    const outDir = path.join(downloadPath, folderName);
    const args = ['/d', dlUrl, '/p', outDir, '/a', '/f', filename];
    execFile(IDM_PATH, args, (err) => {
        if (err)
            console.error('[IDM] spawn error:', err.message);
    });
}
export function downloadDirect(dlUrl, filename, downloadPath, folderName) {
    const outDir = path.join(downloadPath, folderName);
    const outPath = path.join(outDir, filename);
    const psCommand = `New-Item -ItemType Directory -Force -Path '${outDir}'; Invoke-WebRequest -Uri '${dlUrl}' -OutFile '${outPath}'`;
    execFile('powershell', ['-Command', psCommand], (err) => {
        if (err)
            console.error('[Direct] spawn error:', err.message);
    });
}
