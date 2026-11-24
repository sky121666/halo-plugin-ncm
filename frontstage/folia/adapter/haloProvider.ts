import type { SongResult, UnifiedSong } from '../types';
import { create } from 'zustand';
import { setAudioCacheSource } from '../services/audioCache';
import { getSongResourceCacheKey } from '../services/onlineMusic/resourceKeys';
import {
    LISTENING_RANK_RANGES, listeningRankCollection, listeningRankPage, withListenCount,
} from '../services/onlineMusic/listeningRank';
import {
    OnlineProviderError, type AudioQualityPreference, type MediaId, type OnlineMusicProvider, type ProviderCollection,
} from '../types/onlineMusic';
import {
    catalogPath, haloGet, haloSettings, isPrivateHaloSong, musicSearch, playbackLevel, playbackPath, readPlaylist, toCollection, toSong,
    haloTrackId, type HaloCollection, type HaloSong,
} from './haloApi';
import { foliaSessionSignal, foliaUser, requireFoliaBinding } from './haloFoliaBinding';
import {
    getFoliaAlbums, getFoliaPlaylists, haloExplore, haloRecommendations, personalCollection, toFoliaCollection,
} from './haloHomeData';
import { libraryApi, LibraryRequestError, readOwnMusic, toPrivateSong, writeOwnMusic } from './haloLibraryApi';

type Kind = 'song' | 'playlist' | 'album' | 'artist';
const searchSize = 30;
const songDetailsPerRequest = 100;

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
    status: 'ok' | 'unavailable' | 'service' | 'rate_limited';
    url?: string;
    level?: string;
    identity?: 'guest' | 'user';
    gain?: number;
    message?: string;
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

export const useHaloTrackFeedback = create<Map<string, { request: symbol; text?: string; trial?: boolean }>>(() => new Map());

function trackFeedback(id: string) {
    const request = Symbol();
    useHaloTrackFeedback.setState(previous => {
        const next = new Map(previous);
        next.delete(id);
        next.set(id, { request });
        if (next.size > 200) next.delete(next.keys().next().value!);
        return next;
    }, true);
    return (text: string, trial = false) => {
        useHaloTrackFeedback.setState(previous => previous.get(id)?.request === request
            ? new Map(previous).set(id, { request, text, trial }) : previous, true);
    };
}

async function getAudioSource(song: SongResult, quality: AudioQualityPreference) {
    const id = String(song.id);
    const cacheKey = getSongResourceCacheKey('audio', song);
    const signal = foliaSessionSignal();
    const feedback = trackFeedback(id);
    try {
        const cloud = song.sourceRef?.kind === 'online' && song.sourceRef.variant === 'cloud';
        const play = cloud ? await readOwnMusic<PlayAddress>(
            'cloud/' + encodeURIComponent(id) + '/play', { level: playbackLevel(quality) }, requireFoliaBinding(),
        ) : await haloGet<PlayAddress>(playbackPath(id, quality), {}, signal);
        if (play.status !== 'ok' || !play.url) {
            const status = play.status === 'unavailable' ? 404 : play.status === 'rate_limited' ? 429 : 503;
            throw new OnlineProviderError('unavailable', play.message || '音源暂不可用。', 'netease', undefined, status);
        }
        const url = new URL(play.url);
        if (url.protocol !== 'https:') throw new OnlineProviderError('invalid-response', '音源地址格式错误。', 'netease');
        const level = servedQuality(play.level, quality);
        // Ordinary quality on a free catalog track is safe for a bound listener too.
        const freeCatalogAudio = play.identity === 'user' && (song.fee === 0 || song.fee === 8)
            && (play.level === 'standard' || play.level === 'higher' || play.level === 'exhigh');
        const cacheable = !cloud && !isPrivateHaloSong(song) && !play.trial
            && (play.identity === 'guest' || freeCatalogAudio);
        if (!signal.aborted) {
            setAudioCacheSource(cacheKey, cacheable ? { url: url.href, level, signal } : undefined);
        }
        if (play.trial) {
            feedback('试听 ' + Math.round((play.trial.end - play.trial.start) / 1000) + ' 秒', true);
        }
        return {
            url: url.href, quality: level, fetchedAt: Date.now(), expiresAt: Date.now(),
            ...(play.trial ? { preview: true, clipStartMs: play.trial.start } : {}),
            ...(typeof play.gain === 'number' && Number.isFinite(play.gain)
                ? { replayGain: { trackGain: play.gain } } : {}),
        };
    } catch (error) {
        if (!signal.aborted) setAudioCacheSource(cacheKey);
        const status = error instanceof OnlineProviderError ? error.httpStatus
            : error instanceof LibraryRequestError ? error.status : undefined;
        if (status === 404) {
            feedback('暂无音源');
            throw new OnlineProviderError('not-playable', error instanceof Error ? error.message : '暂无音源。',
                'netease', error, 404);
        }
        feedback(status === 429 ? '请求过于频繁' : '音源暂不可用');
        throw error;
    }
}

type HaloLine = { t: number; text: string; tr?: string };
type TimedLyrics = { lines: HaloLine[]; instrumental: boolean; formats?: Record<string, string> };
type CatalogPage<T> = { items: T[]; nextOffset: number; hasMore: boolean; total?: number };
type CloudTrack = { id: string; name: string; artist?: string; song?: HaloSong };

function cloudSong(row: CloudTrack): UnifiedSong {
    const song = toSong({
        ...row.song, id: row.id, name: row.song?.name || row.name,
        artistNames: row.song?.artistNames || row.artist, availability: undefined,
    });
    return {
        ...song,
        sourceRef: {
            kind: 'online', providerId: 'netease', mediaId: row.id, variant: 'cloud',
            providerData: { haloPrivate: true },
        },
    };
}

function lrc(lines: HaloLine[], key: 'text' | 'tr') {
    return lines.flatMap(line => {
        const text = line[key];
        if (!text || !Number.isFinite(line.t) || line.t < 0) return [];
        const minutes = String(Math.floor(line.t / 60000)).padStart(2, '0');
        const seconds = ((line.t % 60000) / 1000).toFixed(3).padStart(6, '0');
        return ['[' + minutes + ':' + seconds + ']' + text.replace(/[\r\n]/g, ' ')];
    }).join('\n');
}

async function getChorusRanges(id: MediaId) {
    try {
        const [data, { parseNeteaseChorusRanges }] = await Promise.all([
            haloGet<unknown>('/music/chorus/' + encodeURIComponent(String(id)), {}, AbortSignal.timeout(2500)),
            import('../utils/lyrics/neteaseProcessing'),
        ]);
        return parseNeteaseChorusRanges(data);
    } catch {
        return [];
    }
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
        userAlbums: true, explore: true, mutations: true, wordByWordLyrics: true, listeningRank: true,
        likes: true, playlistSubscription: true, playlistTrackMutations: true,
        personalFmModes: true, historyRecommendations: true, dailyStyleRecommendations: true,
        userCloud: true, playbackReports: true,
    },
    auth: {
        getLoginStatus: async () => foliaUser(),
        async logout() {
            throw new OnlineProviderError('unsupported', '请在 Halo 个人中心管理网易云账号。', 'netease');
        },
    },
    library: {
        getUserPlaylists: getFoliaPlaylists,
        getUserAlbums: getFoliaAlbums,
        getLikedSongIds: userId => readOwnMusic('likes/ids', {}, requireFoliaBinding(userId)),
        async getListeningRankCollections(user) {
            requireFoliaBinding(user.id);
            return LISTENING_RANK_RANGES.map(range => ({
                ...listeningRankCollection('netease', user, range, []),
                trackCount: undefined,
            }));
        },
        async getCloudCollection(user) {
            if (!user) return null;
            requireFoliaBinding(user.id);
            return {
                id: 'halo-cloud-' + String(user.id), providerId: 'netease', type: 'cloud',
                name: '音乐云盘', coverUrl: user.avatarUrl, creator: user,
            };
        },
    },
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
        songDetailsPerRequest,
        async getSongDetails(ids) {
            const requested = [...new Set(ids.map(String).filter(id => /^\d{1,20}$/.test(id)))];
            const songs = new Map<string, UnifiedSong>();
            for (let index = 0; index < requested.length; index += songDetailsPerRequest) {
                const batch = requested.slice(index, index + songDetailsPerRequest);
                const page = await haloGet<{ items: HaloSong[] }>('/music/songs', { ids: batch.join(',') });
                for (const song of page.items) {
                    songs.set(String(song.id), toSong(song));
                }
            }
            return ids.map(id => songs.get(String(id)) ?? null);
        },
        getAudioSource,
        // 目录版权信息只用于提示。点播时由 Halo 按当前访客身份取址。
        getAvailability: () => ({ state: 'unknown' }),
        audioQualityLevels: ['standard', 'high', 'lossless', 'hires'],
    },
    playbackReports: {
        canReport: song => Boolean(foliaUser() && haloTrackId(song)),
        async reportPlayback(song, report) {
            const songId = haloTrackId(song);
            if (!songId) return;
            const signal = requireFoliaBinding();
            await writeOwnMusic<void>('records/playback', 'POST', signal, {
                songId,
                playedSeconds: Math.round(report.playedSeconds),
                totalSeconds: report.totalSeconds ? Math.round(report.totalSeconds) : undefined,
                quality: report.quality,
            });
            signal.throwIfAborted();
        },
    },
    lyrics: {
        getChorusRanges,
        async getLyrics(song) {
            const cloud = song.sourceRef?.kind === 'online' && song.sourceRef.variant === 'cloud';
            const id = encodeURIComponent(String(song.id));
            const lyrics: Promise<TimedLyrics> = cloud ? readOwnMusic<{ lyric: string }>(
                'cloud/' + id + '/lyric', {}, requireFoliaBinding(),
            ).then(data => ({ lines: [], instrumental: false, formats: { lrc: data.lyric } }))
                : haloGet<TimedLyrics>('/music/lyric/' + id, { view: 'timed' });
            const [data, { processNeteaseLyrics }, chorusRanges] = await Promise.all([
                lyrics,
                import('../utils/lyrics/neteaseProcessing'),
                cloud ? [] : getChorusRanges(song.id),
            ]);
            const formats = data.formats ?? { lrc: lrc(data.lines, 'text'), tlyric: lrc(data.lines, 'tr') };
            const payload = Object.fromEntries(
                Object.entries(formats).map(([format, lyric]) => [format, { lyric }]),
            );
            const result = await processNeteaseLyrics({ ...payload, type: 'netease', pureMusic: data.instrumental });
            return {
                lyrics: result.lyrics,
                mainText: result.mainLrc,
                wordByWordText: result.yrcLrc,
                translationText: result.transLrc,
                romanizationText: result.romanizationLrc,
                isPureMusic: result.isPureMusic,
                chorusRanges,
            };
        },
    },
    catalog: {
        async getCloudTracks(limit, offset, collection) {
            const signal = requireFoliaBinding(collection?.creator?.id);
            const page = await readOwnMusic<CatalogPage<CloudTrack>>(
                'cloud', { limit: Math.min(limit, 100), offset }, signal);
            signal.throwIfAborted();
            return { ...page, items: page.items.map(cloudSong) };
        },
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
            const { artist } = await haloGet<{ artist: HaloCollection }>(catalogPath('artist', id) + '/profile');
            return toCollection(artist, 'artist');
        },
        async getArtistSongs(id, limit, offset) {
            const page = await haloGet<CatalogPage<HaloSong>>(catalogPath('artist', id) + '/songs', { limit, offset });
            return { ...page, items: page.items.map(toSong) };
        },
        async getArtistAlbums(id, limit, offset) {
            const page = await haloGet<CatalogPage<unknown>>(catalogPath('artist', id) + '/albums', { limit, offset });
            return { ...page, items: page.items.map(album => toFoliaCollection(album, 'album')) };
        },
        async getListeningRankTracks(range, limit, offset, collection) {
            const signal = requireFoliaBinding(collection?.creator?.id);
            const records = await libraryApi.rank(range, signal);
            signal.throwIfAborted();
            const songs = (records.items ?? []).flatMap(item => item.song
                ? [withListenCount(toPrivateSong(item.song), item.playCount)] : []);
            return listeningRankPage(songs, limit, offset);
        },
        async getSubscriptionStatus(type, id) {
            const signal = requireFoliaBinding();
            const state = type === 'album' ? await libraryApi.albumSubscription(String(id), signal)
                : await libraryApi.subscription(String(id), signal);
            signal.throwIfAborted();
            return state.subscribed === true;
        },
    },
    mutations: {
        async likeSong(song, liked) {
            const id = typeof song === 'object' ? haloTrackId(song) : String(song);
            if (!id) throw new OnlineProviderError('invalid-response', '曲目缺少网易云 ID。', 'netease');
            const signal = requireFoliaBinding();
            await libraryApi.like(id, liked, signal);
            signal.throwIfAborted();
        },
        async updatePlaylistTracks(operation, playlist, tracks) {
            const id = String(typeof playlist === 'object' ? playlist.id : playlist);
            const ids = tracks.map(track => typeof track === 'object' ? haloTrackId(track) : String(track));
            const songs = ids.filter((songId): songId is string => Boolean(songId));
            if (songs.length !== ids.length) {
                throw new OnlineProviderError('invalid-response', '曲目缺少网易云 ID。', 'netease');
            }
            const signal = requireFoliaBinding();
            await (operation === 'add' ? libraryApi.addTracks(id, songs, signal) : libraryApi.removeTracks(id, songs, signal));
            signal.throwIfAborted();
        },
        async subscribePlaylist(playlist, subscribed) {
            const signal = requireFoliaBinding();
            await libraryApi.subscribe(String(typeof playlist === 'object' ? playlist.id : playlist), subscribed, signal);
            signal.throwIfAborted();
        },
        async subscribeAlbum(id, subscribed) {
            const signal = requireFoliaBinding();
            const state = await libraryApi.subscribeAlbum(String(id), subscribed, signal);
            signal.throwIfAborted();
            if (state.subscribed !== subscribed) {
                throw new OnlineProviderError('unavailable', '网易云还未确认收藏状态，请刷新专辑后查看。', 'netease');
            }
        },
    },
};
