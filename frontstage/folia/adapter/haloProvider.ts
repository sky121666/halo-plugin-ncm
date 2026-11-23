import type { SongResult, UnifiedSong } from '../types';
import { create } from 'zustand';
import {
    OnlineProviderError, type AudioQualityPreference, type MediaId, type OnlineMusicProvider, type ProviderCollection,
} from '../types/onlineMusic';
import { parseLRC } from '../utils/lrcParser';
import {
    catalogPath, haloGet, haloSettings, musicSearch, playbackPath, readPlaylist, toCollection, toSong,
    type HaloCollection, type HaloSong,
} from './haloApi';
import { foliaUser, requireFoliaBinding } from './haloFoliaBinding';
import { getFoliaAlbums, getFoliaPlaylists, haloExplore, haloRecommendations, personalCollection } from './haloHomeData';
import { libraryApi, toPrivateSong } from './haloLibraryApi';

type Kind = 'song' | 'playlist' | 'album' | 'artist';
const searchSize = 30;

function pageOf<T>(items: T[], offset: number, total: number) {
    return { items, total, nextOffset: offset + items.length, hasMore: items.length > 0 && offset + items.length < total };
}

async function search<T, R>(kind: Kind, query: string, offset: number, convert: (item: T) => R) {
    const data = await haloGet<{ items: T[]; total: number }>(musicSearch, {
        q: query, type: kind, page: Math.floor(offset / searchSize) + 1,
    });
    return pageOf((data.items ?? []).map(convert), offset, data.total ?? 0);
}

async function readCollection(type: 'album' | 'artist', id: MediaId) {
    const data = await haloGet<Record<'album' | 'artist', HaloCollection>>(catalogPath(type, id));
    return data[type];
}

function collectionSearch(type: 'album' | 'playlist' | 'artist', query: string, offset: number) {
    return search<HaloCollection, ProviderCollection>(type, query, offset, item => toCollection(item, type));
}

interface PlayAddress {
    status: 'ok';
    url: string;
    level?: string;
    trial?: { start: number; end: number };
}

function servedQuality(level: string | undefined, requested: AudioQualityPreference): AudioQualityPreference {
    switch (level) {
        case 'standard':
        case 'lossless':
        case 'hires':
            return level;
        case 'higher':
        case 'exhigh':
            return 'high';
        default:
            return requested;
    }
}

export const useHaloTrackFeedback = create<Map<string, { request: symbol; text?: string }>>(() => new Map());

function trackFeedback(id: string) {
    const request = Symbol();
    useHaloTrackFeedback.setState(previous => {
        const next = new Map(previous);
        next.delete(id);
        next.set(id, { request });
        if (next.size > 200) next.delete(next.keys().next().value!);
        return next;
    }, true);
    return (text?: string) => {
        useHaloTrackFeedback.setState(previous => previous.get(id)?.request === request
            ? new Map(previous).set(id, { request, text }) : previous, true);
    };
}

async function getAudioSource(song: SongResult, quality: AudioQualityPreference) {
    const id = String(song.id);
    const feedback = trackFeedback(id);
    try {
        const play = await haloGet<PlayAddress>(playbackPath(id, quality));
        const url = new URL(play.url);
        if (url.protocol !== 'https:') throw new OnlineProviderError('invalid-response', '音源地址格式错误。', 'netease');
        if (play.trial) {
            feedback('试听 ' + Math.round((play.trial.end - play.trial.start) / 1000) + ' 秒');
        }
        return {
            url: url.href, quality: servedQuality(play.level, quality), fetchedAt: Date.now(), expiresAt: Date.now(),
            ...(play.trial ? { preview: true, clipStartMs: play.trial.start } : {}),
        };
    } catch (error) {
        if (error instanceof OnlineProviderError && error.httpStatus === 404) {
            feedback('暂无音源');
            throw new OnlineProviderError('not-playable', error.message, 'netease', error, 404);
        }
        feedback(error instanceof OnlineProviderError && error.httpStatus === 429 ? '请求过于频繁' : '音源暂不可用');
        throw error;
    }
}

type HaloLine = { t: number; text: string; tr?: string };
function lrc(lines: HaloLine[], key: 'text' | 'tr') {
    return lines.flatMap(line => {
        const text = line[key];
        if (!text || !Number.isFinite(line.t) || line.t < 0) return [];
        const minutes = String(Math.floor(line.t / 60000)).padStart(2, '0');
        const seconds = ((line.t % 60000) / 1000).toFixed(3).padStart(6, '0');
        return ['[' + minutes + ':' + seconds + ']' + text.replace(/[\r\n]/g, ' ')];
    }).join('\n');
}

export const haloProvider: OnlineMusicProvider = {
    id: 'netease',
    displayName: '网易云音乐',
    shortName: '网易云',
    shortNameKey: 'onlineProviderName.netease',
    mark: { letter: '云', color: '#dc2626' },
    getAvailability: () => ({ configured: true }),
    describeFailure: (operation, error) =>
        operation === 'audio' && error instanceof OnlineProviderError && error.code === 'not-playable'
            ? { case: 'no-audio' } : null,
    capabilities: {
        search: haloSettings.search, playback: true, lyrics: true, auth: true,
        userLibrary: true, playlists: true, albums: true, artists: true, recommendations: true,
        userAlbums: true, explore: true, mutations: false, wordByWordLyrics: false,
    },
    auth: {
        getLoginStatus: async () => foliaUser(),
        async logout() {
            throw new OnlineProviderError('unsupported', '请在 Halo 个人中心管理网易云账号。', 'netease');
        },
    },
    library: { getUserPlaylists: getFoliaPlaylists, getUserAlbums: getFoliaAlbums },
    recommendations: haloRecommendations,
    explore: haloExplore,
    normalizeSong(raw) {
        const song = raw as Partial<UnifiedSong>;
        return song.sourceRef?.kind === 'online' ? song as UnifiedSong : toSong(raw as HaloSong);
    },
    search: {
        searchSongs: (query, _limit, offset) => search('song', query, offset, toSong),
        searchAlbums: (query, _limit, offset) => collectionSearch('album', query, offset),
        searchPlaylists: (query, _limit, offset) => collectionSearch('playlist', query, offset),
        searchArtists: (query, _limit, offset) => collectionSearch('artist', query, offset),
    },
    playback: {
        async getSongDetail(id) {
            try {
                return toSong((await haloGet<{ song: HaloSong }>(catalogPath('song', id))).song);
            } catch (error) {
                if (error instanceof OnlineProviderError && error.httpStatus === 404) return null;
                throw error;
            }
        },
        getAudioSource,
        // 目录版权信息只用于提示。点播时由 Halo 按当前访客身份取址。
        getAvailability: () => ({ state: 'unknown' }),
        audioQualityLevels: ['standard', 'high', 'lossless', 'hires'],
    },
    lyrics: {
        async getLyrics(song) {
            const data = await haloGet<{ lines: HaloLine[]; instrumental: boolean }>(
                '/music/lyric/' + encodeURIComponent(String(song.id)),
            );
            const mainText = lrc(data.lines, 'text');
            const translationText = lrc(data.lines, 'tr');
            return {
                lyrics: mainText ? parseLRC(mainText, translationText) : null,
                mainText, translationText, isPureMusic: data.instrumental,
            };
        },
    },
    catalog: {
        async getPlaylistDetail(id, collection) {
            if (collection?.providerData?.haloPrivate) {
                const result = await libraryApi.playlist(String(id), 0, 1, requireFoliaBinding());
                return result.playlist ? personalCollection(result.playlist) : null;
            }
            return toCollection(await readPlaylist(id, 0), 'playlist');
        },
        async getPlaylistTracks(id, limit, offset, collection) {
            if (collection?.providerData?.haloPrivate) {
                const result = await libraryApi.playlist(String(id), offset, Math.min(limit, 100), requireFoliaBinding());
                const items = (result.items ?? []).map(toPrivateSong);
                return pageOf(items, offset, result.total ?? offset + items.length);
            }
            const list = await readPlaylist(id, limit, offset);
            const items = (list.tracks ?? []).map(toSong);
            return pageOf(items, offset, list.trackCount ?? offset + items.length);
        },
        async getAlbumDetail(id) {
            return toCollection(await readCollection('album', id), 'album');
        },
        async getAlbumTracks(id, limit, offset = 0) {
            const list = (await readCollection('album', id)).tracks ?? [];
            const items = list.slice(offset, limit === undefined ? undefined : offset + limit).map(toSong);
            return pageOf(items, offset, list.length);
        },
        async getArtistDetail(id) {
            return toCollection(await readCollection('artist', id), 'artist');
        },
        async getArtistSongs(id, limit, offset) {
            const all = (await readCollection('artist', id)).hotSongs ?? [];
            return pageOf(all.slice(offset, offset + limit).map(toSong), offset, all.length);
        },
    },
};
