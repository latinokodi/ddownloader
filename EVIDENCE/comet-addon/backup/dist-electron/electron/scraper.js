/* Scraper — ports the Rust extractor logic to Node.js
 * Uses axios + cheerio (equivalent to reqwest + scraper)
 */
import axios from 'axios';
import * as cheerio from 'cheerio';
import path from 'path';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const client = axios.create({
    headers: { 'User-Agent': USER_AGENT },
    timeout: 30000,
});
const ALLOWED_EXTENSIONS = [
    '.mp4',
    '.mkv',
    '.mov',
    '.avi',
    '.wmv',
    '.flv',
    '.webm',
    '.m4v',
    '.ts',
    '.zip',
    '.rar',
    '.7z',
    '.tar',
    '.gz',
];
function sanitizeFilename(filename) {
    return filename.replace(/[<>:"/\\|?*]/g, '_');
}
function hasAllowedExtension(filename) {
    const lower = filename.toLowerCase();
    return ALLOWED_EXTENSIONS.some((ext) => lower.endsWith(ext));
}
function filenameFromTitle(html) {
    const $ = cheerio.load(html);
    const titleText = $('title').first().text().trim();
    if (!titleText.includes('|'))
        return null;
    const titleName = titleText.split('|')[0].trim();
    return titleName && titleName.includes('.') ? titleName : null;
}
export async function getAllAlbumFiles(albumUrl) {
    const { data: html } = await client.get(albumUrl);
    const entries = extractFileLinks(html, albumUrl);
    // Discover page links for pagination
    const $ = cheerio.load(html);
    const pages = new Set();
    const pageRe = /\?page=\d+/;
    $('a').each((_, a) => {
        const href = $(a).attr('href');
        if (href && pageRe.test(href)) {
            try {
                const fullUrl = new URL(href, albumUrl);
                if (fullUrl.href !== albumUrl) {
                    pages.add(fullUrl.href);
                }
            }
            catch { }
        }
    });
    for (const p of pages) {
        try {
            const { data: pageHtml } = await client.get(p);
            const newEntries = extractFileLinks(pageHtml, albumUrl);
            entries.push(...newEntries);
        }
        catch { }
    }
    // Deduplicate
    const seen = new Set();
    const unique = [];
    for (const e of entries) {
        if (!seen.has(e.url)) {
            seen.add(e.url);
            unique.push(e);
        }
    }
    return unique;
}
function extractFileLinks(html, baseUrl) {
    const entries = [];
    const seen = new Set();
    const $ = cheerio.load(html);
    const linkRe = /\/(f|v)\/[^\s"'>]+/;
    const grid = $('div[class*="grid-images"], div[class*="grid-files"], div[class*="grid-root"]').first();
    const anchors = grid.length ? grid.find('a') : $('a');
    anchors.each((_, a) => {
        const href = $(a).attr('href');
        if (href && linkRe.test(href)) {
            try {
                const fullUrl = new URL(href, baseUrl).href;
                if (!seen.has(fullUrl)) {
                    seen.add(fullUrl);
                    const parent = $(a).closest('div[class*="theItem"], div[class*="item"], div[class*="card"]');
                    const name = parent.attr('title')?.trim();
                    // Match the old extractor: preserve Bunkr's displayed title when it is present.
                    // If the title already looks like a filename with an unsupported extension, skip it early.
                    if (name && name.includes('.') && !hasAllowedExtension(name))
                        return;
                    entries.push({ url: fullUrl, name: name || undefined });
                }
            }
            catch { }
        }
    });
    if (!entries.length) {
        const found = html.matchAll(/"\/(f|v)\/([^\s"'<>]+)"/g);
        for (const match of found) {
            try {
                const fullUrl = new URL(`/${match[1]}/${match[2]}`, baseUrl).href;
                if (!seen.has(fullUrl)) {
                    seen.add(fullUrl);
                    entries.push({ url: fullUrl });
                }
            }
            catch { }
        }
    }
    return entries;
}
export async function resolveLink(item) {
    try {
        const { data: html } = await client.get(item.url);
        const jsCdnRe = /var\s+jsCDN\s*=\s*"([^"]+)"/;
        const signUrlRe = /var\s+signUrl\s*=\s*"([^"]+)"/;
        const jsCdnMatch = jsCdnRe.exec(html);
        const signUrlMatch = signUrlRe.exec(html);
        if (jsCdnMatch && signUrlMatch) {
            const jsCdn = jsCdnMatch[1].replace(/\\\//g, '/');
            const signUrl = signUrlMatch[1].replace(/\\\//g, '/');
            const parsedCdn = new URL(jsCdn);
            const pathname = parsedCdn.pathname;
            const signRequestUrl = `${signUrl}?path=${encodeURIComponent(pathname)}`;
            const { data: signData } = await client.get(signRequestUrl, {
                headers: { Referer: item.url },
            });
            if (signData.token && signData.ex !== undefined) {
                const finalUrl = `${jsCdn}?token=${signData.token}&ex=${signData.ex}`;
                let filename = filenameFromTitle(html) || item.name || path.basename(pathname);
                filename = sanitizeFilename(filename);
                return { dlUrl: finalUrl, filename };
            }
        }
        const $ = cheerio.load(html);
        let fileId = $('[data-file-id]').first().attr('data-file-id');
        if (!fileId) {
            fileId = /data-file-id["']?\s*[:=]\s*["'](\d+)/.exec(html)?.[1];
        }
        if (!fileId) {
            $('a[href*="/file/"]').each((_, a) => {
                if (fileId)
                    return;
                const href = $(a).attr('href');
                fileId = href ? /\/file\/(\d+)/.exec(href)?.[1] : undefined;
            });
        }
        if (fileId) {
            const parsedUrl = new URL(item.url);
            const netlocParts = parsedUrl.hostname.split('.');
            const dlHost = parsedUrl.hostname.startsWith('dl.')
                ? parsedUrl.hostname
                : netlocParts.length >= 2
                    ? `dl.${netlocParts.slice(-2).join('.')}`
                    : `dl.${parsedUrl.hostname}`;
            const apiUrl = `https://${dlHost}/api/_001_v2`;
            const dlPageUrl = `https://${dlHost}/file/${fileId}`;
            const { data: meta } = await client.post(apiUrl, { id: fileId }, {
                headers: {
                    Referer: dlPageUrl,
                    'Content-Type': 'application/json',
                },
            });
            const mediafiles = meta?.mediafiles;
            const mediaPath = meta?.path;
            const originalName = meta?.original || item.name;
            if (mediafiles && mediaPath) {
                const rawCdnUrl = `${String(mediafiles).replace(/\/$/, '')}/${String(mediaPath).replace(/^\//, '')}`;
                const parsedCdn = new URL(rawCdnUrl);
                const signUrl = signUrlMatch?.[1]?.replace(/\\\//g, '/') || 'https://glb-apisign.cdn.cr/sign';
                const signRequestUrl = `${signUrl}?path=${encodeURIComponent(parsedCdn.pathname)}`;
                const { data: signData } = await client.get(signRequestUrl, {
                    headers: { Referer: dlPageUrl },
                });
                if (signData.token && signData.ex !== undefined) {
                    const finalUrl = `${rawCdnUrl}?token=${signData.token}&ex=${signData.ex}${originalName ? `&n=${encodeURIComponent(originalName)}` : ''}`;
                    const checkName = originalName || mediaPath;
                    if (hasAllowedExtension(checkName)) {
                        const filename = sanitizeFilename(originalName || path.basename(parsedCdn.pathname));
                        return { dlUrl: finalUrl, filename };
                    }
                }
            }
        }
    }
    catch { }
    return null;
}
