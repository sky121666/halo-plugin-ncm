import type { NcmAccountView } from './generated/ncm-account-view';
import type { NcmCaptchaRequest } from './generated/ncm-captcha-request';
import type { NcmCellphoneLoginRequest } from './generated/ncm-cellphone-login-request';
import type { NcmLoginResult } from './generated/ncm-login-result';
import type { NcmMeLikeCheck } from './generated/ncm-me-like-check';
import type { NcmMeLikeResult } from './generated/ncm-me-like-result';
import type { NcmMePlaylist } from './generated/ncm-me-playlist';
import type { NcmMePlaylistCreateRequest } from './generated/ncm-me-playlist-create-request';
import type { NcmMePlaylistList } from './generated/ncm-me-playlist-list';
import type { NcmMePlaylistTracks } from './generated/ncm-me-playlist-tracks';
import type { NcmMeRankRecords } from './generated/ncm-me-rank-records';
import type { NcmMeRecentRecords } from './generated/ncm-me-recent-records';
import type { NcmMeSong } from './generated/ncm-me-song';
import type { NcmMeSongPage } from './generated/ncm-me-song-page';
import type { NcmMeSubscriptionResult } from './generated/ncm-me-subscription-result';
import type { NcmMeTracksRequest } from './generated/ncm-me-tracks-request';
import type { NcmMeTracksResult } from './generated/ncm-me-tracks-result';
import type { NcmQrConfirmRequest } from './generated/ncm-qr-confirm-request';
import type { NcmQrKey } from './generated/ncm-qr-key';
import { toSong } from './haloApi';

const api = '/apis/uc.api.ncm.halo.run/v1alpha1/';
const mine = 'me/-/';
const segment = encodeURIComponent;

export const bindingPath = api + 'binding';
export const playlistTrackChanges = new EventTarget();

export class LibraryRequestError extends Error {
    constructor(readonly status: number, message: string) { super(message); }
}

async function request<T>(path: string, { signal, method = 'GET', body, timeoutMs = 15000 }: {
    signal?: AbortSignal;
    method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
    body?: object;
    timeoutMs?: number;
} = {}): Promise<T> {
    const timeout = AbortSignal.timeout(timeoutMs);
    const binding = path.startsWith('binding');
    const unconfirmed = binding ? '暂时无法确认绑定请求结果。' : '提交结果暂时无法确认，请刷新后查看。';
    const headers = new Headers({ Accept: 'application/json' });
    if (method !== 'GET') {
        headers.set('X-NCM-Request', '1');
        const csrf = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)?.[1];
        if (csrf) headers.set('X-XSRF-TOKEN', decodeURIComponent(csrf));
    }
    if (body) headers.set('Content-Type', 'application/json');
    const response = await fetch(api + path, {
        method, headers, credentials: 'same-origin', redirect: 'manual', cache: 'no-store',
        body: body ? JSON.stringify(body) : undefined,
        signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    }).catch(error => {
        if (method === 'GET') throw error;
        throw new LibraryRequestError(0, unconfirmed);
    });
    // Halo 的未登录请求可能返回登录页跳转。保持当前 Folia 文档和播放器。
    const status = response.type === 'opaqueredirect' ? 401 : response.status;
    if (status === 204) return undefined as T;
    const data = await response.json().catch(() => null);
    if (response.ok && data) return data as T;
    const message = status === 401 ? '登录 Halo 后可访问个人曲库。'
        : status === 403 ? '请检查网易云绑定或当前账号的访问权限。'
            : status === 429 ? '请求太频繁，请稍后重试。'
                : method !== 'GET' ? unconfirmed
                    : binding ? '暂时无法读取绑定状态。' : '曲库暂时无法加载，请重试。';
    const detail = typeof data?.detail === 'string' && status >= 400 && status < 500 ? data.detail : message;
    throw new LibraryRequestError(status, detail);
}

const paging = (offset: number, limit: number) => '?offset=' + offset + '&limit=' + limit;
export function readOwnMusic<T>(path: string, query: Record<string, string | number | boolean>, signal: AbortSignal) {
    const params = new URLSearchParams();
    Object.entries(query).forEach(([name, value]) => params.set(name, String(value)));
    return request<T>(mine + path + (params.size ? '?' + params : ''), { signal });
}

export function writeOwnMusic<T>(path: string, method: 'POST' | 'PUT', signal: AbortSignal, body?: object) {
    return request<T>(mine + path, { method, signal, body });
}
const subscriptionPath = (id: string) => mine + 'subscriptions/' + segment(id);
const albumSubscriptionPath = (id: string) => mine + 'albums/' + segment(id) + '/subscription';
const tracksPath = (id: string) => 'playlists/' + segment(id) + '/tracks';

export const bindingApi = {
    qrKey: (signal: AbortSignal) => request<NcmQrKey>('binding/qr-key', { method: 'POST', signal, timeoutMs: 20000 }),
    qrCheck: (key: string, signal: AbortSignal) =>
        request<NcmLoginResult>('binding/qr-check?key=' + segment(key), { signal, timeoutMs: 20000 }),
    qrConfirm: (body: NcmQrConfirmRequest, signal: AbortSignal) =>
        request<NcmLoginResult>('binding/-/qr-confirm', { method: 'POST', body, signal, timeoutMs: 30000 }),
    captcha: (body: NcmCaptchaRequest, signal: AbortSignal) =>
        request<NcmLoginResult>('binding/captcha', { method: 'POST', body, signal, timeoutMs: 20000 }),
    cellphone: (body: NcmCellphoneLoginRequest, signal: AbortSignal) =>
        request<NcmLoginResult>('binding/cellphone', { method: 'POST', body, signal, timeoutMs: 30000 }),
};

export const libraryApi = {
    binding: (signal?: AbortSignal) => request<NcmAccountView>('binding', { signal }),
    playlists: (signal: AbortSignal) => request<NcmMePlaylistList>(mine + 'playlists', { signal }),
    playlist: (id: string, offset: number, limit: number, signal: AbortSignal) =>
        request<NcmMePlaylistTracks>(tracksPath(id) + paging(offset, limit), { signal }),
    createPlaylist: (body: NcmMePlaylistCreateRequest) => request<NcmMePlaylist>(mine + 'playlists', { method: 'POST', body }),
    deletePlaylist: (id: string) => request<void>(mine + 'playlists/' + segment(id), { method: 'DELETE' }),
    addTracks: (id: string, songIds: string[], signal?: AbortSignal) => request<NcmMeTracksResult>(tracksPath(id), {
        method: 'POST', body: { songIds }, signal,
    }),
    removeTracks: (id: string, songIds: string[], signal?: AbortSignal) =>
        request<NcmMeTracksResult>(tracksPath(id) + '?songIds=' + segment(songIds.join(',')), { method: 'DELETE', signal }),
    likes: (offset: number, limit: number, signal: AbortSignal) =>
        request<NcmMeSongPage>(mine + 'likes' + paging(offset, limit), { signal }),
    recent: (signal: AbortSignal) => request<NcmMeRecentRecords>(mine + 'records/recent', { signal }),
    rank: (range: 'week' | 'all', signal: AbortSignal) =>
        request<NcmMeRankRecords>(mine + 'records/rank?type=' + range, { signal }),
    checkLikes: (ids: string[], signal: AbortSignal) =>
        request<NcmMeLikeCheck>(mine + 'likes/check?ids=' + segment(ids.join(',')), { signal }),
    like: (id: string, liked: boolean, signal?: AbortSignal) =>
        request<NcmMeLikeResult>(mine + 'likes/' + segment(id), { method: liked ? 'PUT' : 'DELETE', signal }),
    subscription: (id: string, signal: AbortSignal) =>
        request<NcmMeSubscriptionResult>(subscriptionPath(id), { signal }),
    subscribe: (id: string, subscribed: boolean, signal?: AbortSignal) =>
        request<NcmMeSubscriptionResult>(subscriptionPath(id), { method: subscribed ? 'PUT' : 'DELETE', signal }),
    albumSubscription: (id: string, signal: AbortSignal) =>
        request<{ subscribed: boolean }>(albumSubscriptionPath(id), { signal }),
    subscribeAlbum: (id: string, subscribed: boolean, signal: AbortSignal) =>
        request<{ subscribed: boolean }>(albumSubscriptionPath(id), { method: subscribed ? 'PUT' : 'DELETE', signal }),
};

export function toPrivateSong(item: NcmMeSong) {
    const artists = item.artists?.flatMap(({ id, name }) => id && name ? [{ id, name }] : []);
    const album = item.album?.name ? { id: item.album.id ?? '', name: item.album.name } : undefined;
    const song = toSong({
        id: item.id,
        name: item.name ?? '未知曲目',
        artistNames: item.artistNames,
        artists,
        album,
        coverUrl: item.coverUrl,
        durationMs: item.durationMs,
        fee: item.fee,
    });
    if (song.sourceRef.kind !== 'online') return song;
    return { ...song, sourceRef: { ...song.sourceRef, providerData: { haloPrivate: true } } };
}
