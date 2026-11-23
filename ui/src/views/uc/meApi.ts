import { axiosInstance } from '@halo-dev/api-client'
import { UcApiNcmHaloRunV1alpha1MeApi, type NcmMeSong } from '@/api/generated'

export type {
  NcmMePlaylist as MePlaylist,
  NcmMePlaylistTracks as MePlaylistTracks,
  NcmMeProfile as MeProfile,
  NcmMeRankRecords as MeRankRecords,
  NcmMeRecentRecords as MeRecentRecords,
  NcmMeSong as MeSong,
  NcmMeSongPage as MeSongPage,
} from '@/api/generated'

export interface SongItem {
  song: NcmMeSong
  meta?: string
  key?: string
}

export const PRIVACY_PRIVATE = 10
export const MAX_CHECK_IDS = 200

const client = new UcApiNcmHaloRunV1alpha1MeApi(undefined, '', axiosInstance)
const write = { xNCMRequest: '1' }

export const meApi = {
  profile: (signal?: AbortSignal) => client.getMyNcmProfile({ signal }).then((r) => r.data),
  playlists: (signal?: AbortSignal) => client.listMyNcmPlaylists({ signal }).then((r) => r.data),
  playlistTracks: (id: string, offset: number, limit: number, signal?: AbortSignal) =>
    client.listMyNcmPlaylistTracks({ id, offset, limit }, { signal }).then((r) => r.data),
  likes: (offset: number, limit: number, signal?: AbortSignal) =>
    client.listMyNcmLikes({ offset, limit }, { signal }).then((r) => r.data),
  checkLikes: (songIds: string[], signal?: AbortSignal) =>
    client.checkMyNcmLikes({ ids: songIds.join(',') }, { signal }).then((r) => r.data),
  like: (songId: string, liked: boolean) =>
    (liked ? client.likeMyNcmSong({ songId, ...write }) : client.unlikeMyNcmSong({ songId, ...write }))
      .then((r) => r.data),
  recent: (signal?: AbortSignal) => client.listMyNcmRecentRecords({ signal }).then((r) => r.data),
  rank: (type: 'week' | 'all', signal?: AbortSignal) =>
    client.listMyNcmRankRecords({ type }, { signal }).then((r) => r.data),
  createPlaylist: (name: string, privacy: number) =>
    client.createMyNcmPlaylist({ ...write, ncmMePlaylistCreateRequest: { name, privacy } })
      .then((r) => r.data),
  deletePlaylist: (id: string) => client.deleteMyNcmPlaylist({ id, ...write }),
  addTracks: (id: string, songIds: string[]) =>
    client.addMyNcmPlaylistTracks({ id, ...write, ncmMeTracksRequest: { songIds } }).then((r) => r.data),
  removeTracks: (id: string, songIds: string[]) =>
    client.removeMyNcmPlaylistTracks({ id, songIds: songIds.join(','), ...write }).then((r) => r.data),
  subscribe: (playlistId: string, subscribed: boolean) =>
    (subscribed ? client.subscribeMyNcmPlaylist({ playlistId, ...write })
      : client.unsubscribeMyNcmPlaylist({ playlistId, ...write })).then((r) => r.data),
}

export function playbackPath(path: string): string {
  return `${path}?autoplay=1`
}

export function thumb(url: string | undefined, size = 80): string | undefined {
  if (!url || !/\.music\.126\.net\//.test(url)) return url
  return `${url.split('?')[0]}?param=${size}y${size}`
}
