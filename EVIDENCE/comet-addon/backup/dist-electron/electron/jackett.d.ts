/**
 * Build a minimal magnet URI from an info hash.
 * NO trackers are included — this app uses the TorBox debrid service exclusively.
 * Torrenting/P2P never happens on this machine.
 */
export declare function buildMagnet(infoHash: string, title?: string | null): string;
export declare function extractHashFromMagnet(magnet: string): string | null;
export declare class JackettAPI {
    private url;
    private apiKey;
    constructor(url: string, apiKey: string);
    testConnection(): Promise<boolean>;
    search(query: string, limit?: number, offset?: number): Promise<any[]>;
    private _parseXml;
}
