/** Open-source client ID provided by Real-Debrid for public apps.
 *  Scopes: unrestrict, torrents, downloads, user.
 *  https://api.real-debrid.com/ → "Opensource Apps"  */
export declare const RD_OPENSOURCE_CLIENT_ID = "X245A4XAIBGVM";
export declare class RealDebridAPI {
    token?: string | undefined;
    private client;
    constructor(token?: string | undefined);
    /** Step 1: Get a device_code + user_code. */
    static getDeviceCode(clientId?: string): Promise<any>;
    /** Step 2 (poll): Exchange device_code for per-user client_id + client_secret. */
    static getCredentials(clientId: string, deviceCode: string): Promise<any>;
    /** Step 3: Exchange credentials + device_code for access_token + refresh_token. */
    static getToken(clientId: string, clientSecret: string, deviceCode: string): Promise<any>;
    /** Refresh an expired access_token using the stored refresh_token. */
    static refreshAccessToken(clientId: string, clientSecret: string, refreshToken: string): Promise<any>;
    static torrentIdentity(data: any): {
        id: string;
        hash: string;
    };
    static normalizeProgress(value: any, status?: string): number;
    static normalizeTorrent(data: any): any;
    private request;
    getUserInfo(): Promise<any>;
    getTraffic(): Promise<any>;
    addMagnet(magnet: string): Promise<any>;
    addTorrentFile(fileBuffer: Buffer, fileName?: string): Promise<any>;
    addTorrentFromUrl(torrentUrl: string): Promise<any>;
    getTorrents(): Promise<any>;
    getTorrentInfo(torrentId: string): Promise<any>;
    unrestrictLink(downloadUrl: string): Promise<any>;
    deleteTorrent(torrentId: string): Promise<any>;
    selectFiles(torrentId: string, fileIds: string[]): Promise<any>;
}
