export declare class TorboxAPI {
    token?: string | undefined;
    private readonly baseUrl;
    private client;
    constructor(token?: string | undefined);
    static torrentIdentity(data: any): {
        id: string;
        hash: string;
    };
    static normalizeProgress(value: any, state?: string): number;
    static normalizeTorrent(data: any): any;
    private request;
    getDeviceCode(): Promise<any>;
    getToken(deviceCode: string): Promise<any>;
    getUserInfo(): Promise<any>;
    addMagnet(magnet: string): Promise<any>;
    /** Upload a .torrent file as a Buffer (multipart/form-data) */
    addTorrentFile(fileBuffer: Buffer, fileName?: string): Promise<any>;
    /** Download a .torrent file from a URL and upload to TorBox */
    addTorrentFromUrl(torrentUrl: string): Promise<any>;
    getTorrents(): Promise<any>;
    getTorrentInfo(torrentId: string): Promise<any>;
    getDownloadLink(torrentId: string, fileId: string): Promise<any>;
    controlTorrent(torrentId: string, operation: string): Promise<any>;
}
