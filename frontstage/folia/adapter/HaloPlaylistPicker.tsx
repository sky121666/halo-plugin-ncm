import { useEffect, useState } from 'react';
import PlaylistSelectionDialog from '../components/shared/PlaylistSelectionDialog';
import { getSongArtistLabel, getSongCoverUrl } from '../services/onlineMusic/songMetadata';
import { setStatusMessage } from '../stores/useStatusMessageStore';
import type { SongResult } from '../types';
import type { NcmMePlaylist } from './generated/ncm-me-playlist';
import { haloTrackId } from './haloApi';
import { libraryApi, playlistTrackChanges } from './haloLibraryApi';
import { CreatePlaylistDialog } from './HaloPlaylistEditor';
import { PlaylistFeedback, playlistIssue, type PlaylistIssue } from './PlaylistFeedback';
import { useHaloAccount } from './HaloAccount';

export default function HaloPlaylistPicker({ requested, isDaylight, onClose, onReview }: {
    requested: SongResult[]; isDaylight: boolean; onClose: () => void; onReview: (id?: string) => void;
}) {
    const { state: session } = useHaloAccount();
    const account = session.kind === 'account' ? session.account : undefined;
    // 播放下一首时，选择器继续处理打开时选择的曲目。
    const [songs] = useState(() => requested);
    const ids = [...new Set(songs.map(haloTrackId).filter((id): id is string => !!id))];
    const supported = ids.length > 0 && ids.length <= 100 && songs.every(haloTrackId);
    const [items, setItems] = useState<NcmMePlaylist[]>([]);
    const [loaded, setLoaded] = useState(false);
    const [failure, setFailure] = useState<unknown>();
    const [attempt, setAttempt] = useState(0);
    const [savingIssue, setSavingIssue] = useState<(PlaylistIssue & { playlistId: string })>();
    const [creating, setCreating] = useState(false);
    useEffect(() => {
        if (!supported) return;
        const controller = new AbortController();
        setLoaded(false);
        setFailure(undefined);
        libraryApi.playlists(controller.signal).then(data => {
            if (!controller.signal.aborted) setItems(data.items.filter(item => item.own && !item.likedSongs));
        }).catch(error => {
            if (!controller.signal.aborted) setFailure(error);
        }).finally(() => { if (!controller.signal.aborted) setLoaded(true); });
        return () => controller.abort();
    }, [attempt, supported, session.kind, account?.userId, account?.boundAt]);

    const pick = async (id: string | number) => {
        const playlist = items.find(item => item.id === id);
        if (!playlist || !supported || savingIssue?.action === 'review') return false;
        setSavingIssue(undefined);
        try {
            await libraryApi.addTracks(playlist.id, ids);
            playlistTrackChanges.dispatchEvent(new CustomEvent('change', { detail: playlist.id }));
            setStatusMessage({ type: 'success', text: '已加入「' + (playlist.name ?? '歌单') + '」' });
            return true;
        } catch (error) {
            setSavingIssue({ ...playlistIssue(error, true), playlistId: playlist.id });
            return false;
        }
    };
    if (creating) return <CreatePlaylistDialog isDaylight={isDaylight} onClose={() => setCreating(false)}
        onReview={() => onReview()}
        onCreated={playlist => {
            setItems(current => [playlist, ...current.filter(item => item.id !== playlist.id)]);
            setCreating(false);
        }} />;

    const reviewRequired = savingIssue?.action === 'review';
    const notice = !supported ? <p role="status">{ids.length > 100 ? '一次最多可加入 100 首曲目。' : '没有可加入歌单的曲目。'}</p>
        : !loaded ? <p role="status">正在读取歌单…</p>
            : failure ? <PlaylistFeedback issue={playlistIssue(failure)} onRetry={() => setAttempt(value => value + 1)} />
                : savingIssue && <PlaylistFeedback issue={savingIssue}
                    onReview={() => onReview(savingIssue.status === 404 ? undefined : savingIssue.playlistId)} />;
    const first = songs[0];
    return <PlaylistSelectionDialog isOpen isDaylight={isDaylight} title="加入歌单" onClose={onClose}
        subject={first ? {
            title: songs.length === 1 ? first.name : songs.length + ' 首曲目',
            subtitle: songs.length === 1 ? getSongArtistLabel(first) : undefined,
            coverUrls: songs.slice(0, 3).flatMap(song => {
                const url = getSongCoverUrl(song);
                return url ? [url] : [];
            }),
        } : undefined}
        playlists={loaded && !failure ? items.map(item => ({
            id: item.id, name: item.name ?? '未命名歌单', coverUrl: item.coverUrl,
            description: [item.trackCount === undefined ? undefined : item.trackCount + ' 首',
                item.privacy ? '私密' : undefined].filter(Boolean).join(' · '),
        })) : []}
        status={notice || undefined}
        onSelect={pick}
        selectionDisabled={reviewRequired}
        createLabel="新建歌单"
        onCreate={supported && loaded && !failure && !reviewRequired ? () => setCreating(true) : undefined}
    />;
}
