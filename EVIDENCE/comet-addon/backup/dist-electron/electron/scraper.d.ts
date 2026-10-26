export interface FileEntry {
    url: string;
    name?: string;
}
export declare function getAllAlbumFiles(albumUrl: string): Promise<FileEntry[]>;
export interface ResolvedLink {
    dlUrl: string;
    filename: string;
}
export declare function resolveLink(item: FileEntry): Promise<ResolvedLink | null>;
