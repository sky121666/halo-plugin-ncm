import type { MediaId, OnlineExploreProvider, ProviderCollection, ProviderPage } from '../types/onlineMusic';
import type { NcmMePlaylist } from './generated/ncm-me-playlist';
import { haloGet, musicHome, readPlaylist, toCollection, toSong, type HaloCollection, type HaloSong } from './haloApi';
import { foliaSessionSignal, foliaUser, requireFoliaBinding } from './haloFoliaBinding';
import { libraryApi, readOwnMusic } from './haloLibraryApi';

type SourceArtist = { id: string; name: string };
type SourceCreator = { id: string; nickname: string };
type SourceCollection = {
    id: string;
    name: string;
    picUrl?: string;
    coverImgUrl?: string;
    size?: number;
    trackCount?: number;
    playCount?: number;
    publishTime?: number;
    description?: string;
    artists?: SourceArtist[];
    creator?: SourceCreator;
};

function toFoliaCollection(raw: unknown, type = 'playlist') {
    const row = raw as SourceCollection;
    return toCollection({
        id: row.id, name: row.name, coverUrl: row.coverImgUrl || row.picUrl,
        trackCount: row.trackCount ?? row.size, playCount: row.playCount,
        publishedAt: row.publishTime, description: row.description,
        artists: row.artists, creator: row.creator,
    }, type === 'album' ? 'album' : 'playlist');
}

export function personalCollection(row: NcmMePlaylist): ProviderCollection {
    return {
        ...toCollection({
            id: row.id, name: row.name ?? '未命名歌单', coverUrl: row.coverUrl,
            trackCount: row.trackCount, playCount: row.playCount, description: row.description,
            creator: row.creatorId ? { id: row.creatorId, nickname: row.creatorName ?? '' } : undefined,
        }, 'playlist'),
        isOwned: row.own, isLiked: row.likedSongs,
        ...(row.privacy ? { providerData: { haloPrivate: true } } : {}),
    };
}

export async function getFoliaPlaylists(userId: MediaId, _limit: number, offset: number): Promise<ProviderPage<ProviderCollection>> {
    const signal = requireFoliaBinding(userId);
    const { items = [], userId: owner } = await libraryApi.playlists(signal);
    signal.throwIfAborted();
    if (String(owner) !== String(userId)) throw new DOMException('绑定已变化', 'AbortError');
    // Halo already returned the complete ordered list in one request.
    return { items: items.map(personalCollection), hasMore: false, nextOffset: offset + items.length };
}

export async function getFoliaAlbums(userId: MediaId, limit: number, offset: number) {
    const { albums, more } = await readOwnMusic<{ albums: SourceCollection[]; more: boolean }>(
        'albums', { limit, offset }, requireFoliaBinding(userId));
    return { items: albums.map(row => toFoliaCollection(row, 'album')), hasMore: more, nextOffset: offset + albums.length };
}

const daily = (refresh = false) => readOwnMusic<{ songs: HaloSong[] }>(
    'recommendations/daily', { refresh }, requireFoliaBinding());
const recommended = () => readOwnMusic<{ recommend: SourceCollection[] }>(
    'recommendations/playlists', {}, requireFoliaBinding());

export const haloRecommendations = {
    getDailySongs: async (refresh?: boolean) => (await daily(refresh)).songs.map(toSong),
    getPersonalFm: async () => (await readOwnMusic<{ songs: HaloSong[] }>(
        'recommendations/fm', {}, requireFoliaBinding())).songs.map(toSong),
    getRecommendedCollections: async (limit: number) =>
        (await recommended()).recommend.slice(0, limit).map(row => toFoliaCollection(row)),
};

type HomeBlocks = { blocks: unknown[]; cursor?: string; hasMore?: boolean };
type ExploreReader = import('../services/onlineMusic/netease/neteaseExplore').NeteaseExploreReads;
type Showcase = {
    leadPlaylist?: HaloCollection;
    featuredPlaylists?: HaloCollection[];
    publicPlaylistShelf?: HaloCollection[];
    recentSongs?: HaloSong[];
    weekRank?: HaloSong[];
    dailySongs?: HaloSong[];
};

function showcaseBlock<T>(blockCode: string, title: string, items: readonly T[], resource: (item: T) => object) {
    return { blockCode, uiElement: { mainTitle: { title } }, creatives: [{ resources: items.map(resource) }] };
}

function sitePlaylist(row: HaloCollection) {
    return {
        resourceType: 'list', resourceId: row.id,
        uiElement: { mainTitle: { title: row.name }, image: { imageUrl: row.coverUrl } },
        resourceExtInfo: { playCount: row.playCount },
    };
}

function siteSong(row: HaloSong) {
    return { resourceType: 'song', resourceId: row.id, resourceExtInfo: { songData: row } };
}

async function getHomeBlocks(cursor: string | undefined, signal: AbortSignal) {
    const params: Record<string, string> = cursor ? { cursor } : {};
    const home = foliaUser()
        ? readOwnMusic<{ data: HomeBlocks }>('recommendations/explore', params, signal)
        : haloGet<{ data: HomeBlocks }>('/music/explore/home', params, signal);
    if (cursor) return home;
    const [feed, site] = await Promise.allSettled([home, haloGet<Showcase>(musicHome, {}, signal)]);
    signal.throwIfAborted();
    if (feed.status === 'rejected' && site.status === 'rejected') throw feed.reason;
    const result = feed.status === 'fulfilled' ? feed.value : { data: { blocks: [] } };
    if (site.status === 'rejected') return result;
    const data = site.value;
    const featured = data.featuredPlaylists ?? [];
    const publicLists = data.publicPlaylistShelf ?? [];
    if (!featured.length && data.leadPlaylist) publicLists.unshift(data.leadPlaylist);
    return { data: { ...result.data, blocks: [
        showcaseBlock('halo-featured', '站长精选', featured, sitePlaylist),
        showcaseBlock('halo-public', '站长公开歌单', publicLists, sitePlaylist),
        showcaseBlock('halo-plays', '站长最近播放', data.recentSongs ?? [], siteSong),
        showcaseBlock('halo-rank', '站长听歌排行', data.weekRank ?? [], siteSong),
        showcaseBlock('halo-daily', '站长每日推荐', data.dailySongs ?? [], siteSong),
        ...result.data.blocks,
    ] } };
}

function exploreReads(signal: AbortSignal): ExploreReader {
    const get = <T>(section: string, params: Record<string, string | number> = {}) =>
        haloGet<T>('/music/explore/' + section, params, signal);
    return {
        hasAccountSession: () => Boolean(foliaUser()),
        getHomeBlocks: cursor => getHomeBlocks(cursor, signal),
        getToplists: () => get<{ list: SourceCollection[] }>('charts'),
        getNewSongs: () => get<{ songs: HaloSong[] }>('new-songs'),
        getNewAlbums: (limit = 12, offset = 0) => get<{ albums: SourceCollection[]; total: number }>('new-albums', { limit, offset }),
        getHotPlaylists: (limit = 12, offset = 0) => get<{ playlists: SourceCollection[]; more: boolean }>('hot-playlists', { limit, offset }),
        getSimilarSongs: id => get<{ songs: HaloSong[] }>('similar-songs', { id }),
        getPlaylistTracks: async (id, limit = 12, offset = 0) => {
            const list = await readPlaylist(id, limit, offset, signal);
            return { songs: list.tracks ?? [] };
        },
        getDailyRecommendedSongs: () => daily(),
        getDailyRecommendedPlaylists: () => recommended(),
    };
}

let explorer: { signal: AbortSignal; provider: Promise<OnlineExploreProvider> } | undefined;
function exploreProvider() {
    const signal = foliaSessionSignal();
    if (explorer?.signal === signal) return explorer.provider;
    const provider = import('../services/onlineMusic/netease/neteaseExplore').then(({ createNeteaseExplore }) =>
        createNeteaseExplore({ song: item => toSong(item as HaloSong), collection: toFoliaCollection }, exploreReads(signal)));
    explorer = { signal, provider };
    return provider;
}

export const haloExplore: OnlineExploreProvider = {
    getFeed: async (cursor, listened) => (await exploreProvider()).getFeed(cursor, listened),
    getSectionItems: async cursor => (await exploreProvider()).getSectionItems!(cursor),
};
