import type { Album, Artist, SongResult, UnifiedSong } from '../types';
import { OnlineProviderError, type AudioQualityPreference, type OmniSearchKind, type ProviderCollection } from '../types/onlineMusic';

export type HaloPlayMode = 'order' | 'loop' | 'single' | 'shuffle';
export type HaloCollectionType = 'playlist' | 'album' | 'artist';
export type HaloDirectEntry =
    | { type: 'song'; path: string; item: HaloSong }
    | { type: 'error'; path: string; item: { name: string; message: string } };
export type HaloPageEntry = HaloDirectEntry | { type: 'library'; path: string; item: { name: string } };
export type HaloEntry = HaloPageEntry | { type: HaloCollectionType; path: string; item: HaloCollection };

export const haloSettings: {
    title: string;
    description: string;
    search: boolean;
    suggest: boolean;
    pageSize: number;
    volume: number;
    mode: HaloPlayMode;
    resume: boolean;
    version: string;
    entry?: HaloEntry;
} = JSON.parse(globalThis.document?.getElementById('root')?.dataset.ncmConfig ?? '{}');

export const musicHome = '/music';
export const musicSearch = '/music/search';
export const musicLibrary = '/music/library';
export const sourceHref = '/plugins/plugin-ncm/assets/static/folia/source/halo-adaptation.tar.gz';

export type LibraryTarget =
    | { tab: 'playlists'; playlist?: string }
    | { tab: 'likes' | 'recent' }
    | { tab: 'rank'; range: 'week' | 'all' };
export type LibraryNavigate = (target?: LibraryTarget, options?: { replace?: boolean }) => void;

export function libraryTarget(path: string): LibraryTarget {
    const params = new URL(path, 'https://folia.invalid').searchParams;
    const tab = params.get('tab');
    if (tab === 'rank') return { tab, range: params.get('range') === 'all' ? 'all' : 'week' };
    if (tab === 'likes' || tab === 'recent') return { tab };
    const playlist = params.get('playlist') ?? '';
    return { tab: 'playlists', ...(/^[1-9]\d{0,19}$/.test(playlist) ? { playlist } : {}) };
}

export function libraryHref(target: LibraryTarget = { tab: 'playlists' }) {
    const params = new URLSearchParams();
    if (target.tab !== 'playlists') params.set('tab', target.tab);
    if (target.tab === 'playlists' && target.playlist) params.set('playlist', target.playlist);
    if (target.tab === 'rank' && target.range === 'all') params.set('range', target.range);
    return params.size ? musicLibrary + '?' + params.toString() : musicLibrary;
}

const qualityLevels: Record<AudioQualityPreference, string> = {
    standard: 'standard', high: 'exhigh', ultra: 'exhigh', lossless: 'lossless', hires: 'hires',
};

export const playbackPath = (id: string, quality: AudioQualityPreference) =>
    '/music/play/' + encodeURIComponent(id) + '?level=' + qualityLevels[quality];

export function searchKind(value: string | null): OmniSearchKind {
    return value === 'album' || value === 'playlist' || value === 'artist' ? value : 'song';
}

export function searchHref(query: string, kind: OmniSearchKind) {
    const params = new URLSearchParams();
    if (query) params.set('q', query);
    if (kind !== 'song') params.set('type', kind);
    return params.size ? musicSearch + '?' + params.toString() : musicSearch;
}

export async function haloGet<T>(path: string, params: Record<string, string | number> = {}, signal?: AbortSignal): Promise<T> {
    const url = new URL(path, location.origin);
    url.searchParams.set('format', 'json');
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
    const timeout = AbortSignal.timeout(12000);
    const response = await fetch(url, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
        signal: signal ? AbortSignal.any([timeout, signal]) : timeout,
    });
    const data = await response.json().catch(() => null);
    if (response.ok && data) return data as T;
    const message = response.status === 429 ? '请求太频繁，请稍后重试。'
        : response.status === 400 ? '搜索词最多 100 个字符。'
            : response.status === 404 ? '没有找到可用内容。' : '音乐服务暂时不可用。';
    throw new OnlineProviderError('unavailable', data?.message || message, 'netease', undefined, response.status);
}

export interface HaloSong {
    id: string;
    name: string;
    durationMs?: number;
    artists?: Artist[];
    album?: Album;
    artistNames?: string;
    coverUrl?: string;
    aliases?: string[];
    translations?: string[];
    fee?: number;
    availability?: 'full' | 'trial' | 'unavailable';
}

export interface HaloCollection {
    id: string;
    name: string;
    coverUrl?: string;
    description?: string;
    briefDesc?: string;
    tracks?: HaloSong[];
    hotSongs?: HaloSong[];
    trackCount?: number;
    musicSize?: number;
    albumSize?: number;
    playCount?: number;
    artists?: Artist[];
    aliases?: string[];
    publishedAt?: number;
    publisher?: string;
    creator?: { id: string; nickname: string; avatarUrl?: string };
}

export function toSong(song: HaloSong): UnifiedSong {
    return {
        id: String(song.id),
        name: song.name,
        durationMs: song.durationMs ?? 0,
        artists: song.artists?.length ? song.artists : (song.artistNames ? [{ id: '', name: song.artistNames }] : []),
        album: { id: song.album?.id ?? '', name: song.album?.name ?? '', coverUrl: song.coverUrl || song.album?.coverUrl },
        aliases: song.aliases,
        translatedNames: song.translations,
        fee: song.fee ?? undefined,
        sourceRef: {
            kind: 'online', providerId: 'netease', mediaId: String(song.id),
            ...(song.availability ? { providerData: { haloAvailability: song.availability } } : {}),
        },
    };
}

export function isPrivateHaloSong(song: SongResult) {
    return song.sourceRef?.kind === 'online' && song.sourceRef.providerData?.haloPrivate === true;
}

export function haloTrackId(song: SongResult) {
    const ref = song.sourceRef;
    return ref?.kind === 'online' && ref.providerId === 'netease' && /^[1-9]\d{0,19}$/.test(ref.mediaId)
        ? ref.mediaId : undefined;
}

export const canSaveHaloTrack = (song: SongResult) => haloTrackId(song) !== undefined;

export function toCollection(data: HaloCollection, type: 'playlist' | 'album' | 'artist'): ProviderCollection {
    return {
        providerId: 'netease',
        type,
        id: String(data.id),
        name: data.name,
        coverUrl: data.coverUrl,
        description: data.description || data.briefDesc,
        trackCount: data.trackCount ?? data.musicSize,
        albumCount: data.albumSize,
        playCount: data.playCount,
        artists: data.artists,
        aliases: data.aliases,
        publishedAt: data.publishedAt,
        publisher: data.publisher,
        creator: data.creator ?? undefined,
    };
}

export function catalogPath(type: 'playlist' | 'album' | 'artist' | 'song', id: string | number) {
    return '/music/' + type + '/' + encodeURIComponent(String(id));
}

export function readPlaylist(id: string | number, limit: number, offset = 0, signal?: AbortSignal) {
    return haloGet<{ playlist: HaloCollection }>(catalogPath('playlist', id), { limit, offset }, signal)
        .then(data => data.playlist);
}
