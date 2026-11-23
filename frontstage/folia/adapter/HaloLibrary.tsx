import { useCallback, useEffect, useId, useMemo, useRef, useState, type ComponentPropsWithoutRef } from 'react';
import { ArrowLeft, Bookmark, ChevronRight, Disc3, Ellipsis, Heart, ListPlus, LoaderCircle, Play, Plus, Trash2 } from 'lucide-react';
import SearchResultRow from '../components/app/search/SearchResultRow';
import { EXPLORE_TOKENS } from '../components/app/home/explore/ExploreSections';
import Menu, { type MenuItem } from '../components/modal/settings/kit/Menu';
import { settingsTokens, useSettingsKitTheme } from '../components/modal/settings/kit/kitTheme';
import { kitMotionTokens, useKitReducedMotion } from '../components/modal/settings/kit/motion';
import { isSongUnavailable } from '../services/onlineMusic/songAvailability';
import type { SongResult } from '../types';
import type { MediaId, OmniCollection } from '../types/onlineMusic';
import { openAddSongsToPlaylist } from '../stores/useAddToPlaylistStore';
import type { NcmMePlaylist } from './generated/ncm-me-playlist';
import type { NcmMePlaylistTracks } from './generated/ncm-me-playlist-tracks';
import type { NcmMeRecentRecords } from './generated/ncm-me-recent-records';
import type { NcmMeSong } from './generated/ncm-me-song';
import type { NcmMeSongPage } from './generated/ncm-me-song-page';
import { HaloMusicHeader } from './HaloMusicHeader';
import { useHaloAccount } from './HaloAccount';
import { canSaveHaloTrack, haloSettings, libraryHref, libraryTarget, toCollection, type LibraryNavigate, type LibraryTarget } from './haloApi';
import { libraryApi, LibraryRequestError, playlistTrackChanges, toPrivateSong } from './haloLibraryApi';
import { HaloPlaylistEditor, type PlaylistEdit } from './HaloPlaylistEditor';
import { PlaylistFeedback, playlistIssue, type PlaylistIssue } from './PlaylistFeedback';
import { useLibraryReactions, type ReactionState } from './libraryReactions';
import './haloLibrary.css';

type Playback = {
    onPlay: (song: SongResult, queue: SongResult[]) => void;
    onAdd: (song: SongResult) => void;
    onOpen: (collection: OmniCollection) => void;
};
type Props = Playback & { path: string; isDaylight: boolean; onNavigate: LibraryNavigate; onHome?: () => void; onSearch: () => void };
type Row = { song: NcmMeSong; annotation?: string };

const tabs: readonly { target: LibraryTarget; label: string }[] = [
    { target: { tab: 'playlists' }, label: '我的歌单' },
    { target: { tab: 'likes' }, label: '喜欢的歌' },
    { target: { tab: 'recent' }, label: '最近播放' },
    { target: { tab: 'rank', range: 'week' }, label: '听歌排行' },
];
const pageSize = Math.max(1, Math.min(100, haloSettings.pageSize || 30));
const dateFormat = new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
const rowStyle = {};

type ReadResult<T> = { value: T; error?: never } | { value?: T; error: unknown };
function useRead<T>(load: ((signal: AbortSignal) => Promise<T>) | null, retain?: 'loading' | 'transient-error') {
    const [attempt, setAttempt] = useState(0);
    const [state, setState] = useState<{ load: typeof load; attempt: number; result: ReadResult<T> }>();
    const retry = useCallback(() => setAttempt(value => value + 1), []);
    useEffect(() => {
        if (!load) return;
        const controller = new AbortController();
        load(controller.signal).then(
            value => { if (!controller.signal.aborted) setState({ load, attempt, result: { value } }); },
            error => {
                if (controller.signal.aborted) return;
                const status = error instanceof LibraryRequestError ? error.status : undefined;
                const transient = status === undefined || status === 0 || status === 408 || status === 429 || status >= 500;
                setState(previous => ({ load, attempt, result: {
                    error,
                    value: retain === 'transient-error' && transient && previous?.load === load
                        ? previous.result.value : undefined,
                } }));
            },
        );
        return () => controller.abort();
    }, [load, attempt, retain]);
    const matching = state?.load === load ? state : undefined;
    const loading = load !== null && matching?.attempt !== attempt;
    const result = matching && (!loading || (retain && matching.result.value !== undefined))
        ? matching.result : undefined;
    return { result, retry, loading };
}

function LibraryLoginNotice() {
    return <div className="halo-library-state">
        <h2>登录后查看个人曲库</h2>
        <p>请在音乐首页或播放器的「音质」面板登录 Halo。</p>
    </div>;
}

function ErrorNotice({ error, retry }: { error: unknown; retry: () => void }) {
    if (error instanceof LibraryRequestError && error.status === 401) return <LibraryLoginNotice />;
    return <PlaylistFeedback issue={playlistIssue(error)} onRetry={retry} />;
}

function LibraryLoading({ text, rows = 0 }: { text: string; rows?: number }) {
    return <div className="halo-library-loading" role="status" data-halo-library-loading>
        <p><LoaderCircle size={17} className="halo-library-spin" aria-hidden="true" />{text}</p>
        {rows > 0 && <div className="halo-library-skeleton" aria-hidden="true">
            {Array.from({ length: rows }, (_, index) => <div key={index}><i /><span><i /><i /></span></div>)}
        </div>}
    </div>;
}

function LibraryEmpty({ message, action }: { message: string; action?: React.ReactNode }) {
    return <div className="halo-library-empty-state" data-halo-library-empty>
        <p>{message}</p>
        {action}
    </div>;
}

function ReactionFeedback({ state, label, review }: { state?: ReactionState; label: string; review: () => void }) {
    return <PlaylistFeedback issue={state?.issue} onReview={review} reviewLabel={label}
        onRetry={state?.needsReview && state.issue?.action !== 'review' ? review : undefined} />;
}

function LibraryLink({ to, navigate, ...props }: Omit<ComponentPropsWithoutRef<'a'>, 'href' | 'onClick'> & {
    to: LibraryTarget;
    navigate: LibraryNavigate;
}) {
    return <a {...props} href={libraryHref(to)} onClick={event => {
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        navigate(to);
    }} />;
}

function whenPlayed(value?: string) {
    const date = new Date(value ?? '');
    return Number.isNaN(date.valueOf()) ? undefined : dateFormat.format(date);
}

function MoreActions({ label, items, kind }: { label: string; items: MenuItem[]; kind: 'playlist' | 'track' }) {
    const anchor = useRef<HTMLButtonElement>(null);
    const menuId = useId();
    const [open, setOpen] = useState(false);
    return <>
        <button ref={anchor} type="button" className="halo-library-icon-button" data-halo-more={kind}
            aria-label={label} title={label} aria-haspopup="menu" aria-controls={open ? menuId : undefined} aria-expanded={open}
            onClick={() => setOpen(value => !value)}
            onKeyDown={event => {
                if (event.key !== 'ArrowDown') return;
                event.preventDefault();
                setOpen(true);
            }}>
            <Ellipsis size={20} aria-hidden="true" />
        </button>
        <Menu id={menuId} anchorRef={anchor} open={open} onClose={() => setOpen(false)} align="end" label={label}
            items={items.map(item => ({ ...item, onSelect: () => {
                anchor.current?.focus({ preventScroll: true });
                item.onSelect();
            } }))} />
    </>;
}

function Playlists({ title, items, navigate, action, empty, status }: {
    title: string; items: { playlist: NcmMePlaylist; playedAt?: string }[]; navigate: LibraryNavigate;
    action?: React.ReactNode; empty: string; status?: React.ReactNode;
}) {
    return <section className="halo-library-section">
        <div className="halo-library-row-heading"><h2>{title}</h2>{action}</div>
        {status ?? (items.length === 0 ? <LibraryEmpty message={empty} /> :
            <ul className="halo-library-playlists">{items.map(({ playlist, playedAt }, index) =>
                <li key={playlist.id + ':' + index}>
                    <LibraryLink to={playlist.likedSongs ? { tab: 'likes' } : { tab: 'playlists', playlist: playlist.id }}
                        navigate={navigate} className="halo-library-list-link">
                        {playlist.coverUrl ? <img src={playlist.coverUrl} alt="" loading="lazy" referrerPolicy="no-referrer" /> :
                            <Disc3 size={32} aria-hidden="true" />}
                        <span className="halo-library-list-label">
                            <strong>{playlist.name ?? '未命名歌单'}</strong>
                            <small>{[playlist.trackCount === undefined ? null : playlist.trackCount + ' 首',
                                playlist.privacy ? '私密' : null, whenPlayed(playedAt)].filter(Boolean).join(' · ')}</small>
                        </span>
                        <ChevronRight size={17} aria-hidden="true" />
                    </LibraryLink>
                </li>)}</ul>)}
    </section>;
}

function Subscription({ playlist }: { playlist: NcmMePlaylist }) {
    const load = useCallback((signal: AbortSignal) => libraryApi.subscription(playlist.id, signal), [playlist.id]);
    const read = useRead(playlist.subscribed === undefined ? load : null);
    const reactions = useLibraryReactions('favorite');
    const state = reactions.entries[playlist.id];
    const current = state?.value ?? playlist.subscribed ?? read.result?.value?.subscribed;
    const busy = read.loading || !!state?.pending;
    const disabled = busy || current === undefined || state?.needsReview;
    const label = state?.pending?.kind === 'write' ? (state.pending.next ? '正在收藏…' : '正在取消…')
        : busy ? '正在核对…' : state?.needsReview || current === undefined ? '收藏待核对'
            : current ? '已收藏' : '收藏歌单';
    return <div className="halo-library-subscription">
        <button type="button" className="halo-gallery-action" aria-pressed={state?.needsReview ? undefined : current}
            aria-busy={busy || undefined} aria-disabled={disabled || undefined}
            aria-label="收藏歌单" title={label} onClick={() => {
                if (!disabled && current !== undefined) reactions.toggle(playlist.id, current);
            }} data-halo-subscription>
            {busy ? <LoaderCircle size={16} className="halo-library-spin" aria-hidden="true" />
                : <Bookmark size={16} fill={current && !state?.needsReview ? 'currentColor' : 'none'} aria-hidden="true" />}
            <span aria-live="polite">{label}</span>
        </button>
        {read.result?.error && state?.value === undefined ? <PlaylistFeedback
            issue={playlistIssue(read.result.error, false, '收藏')} onRetry={read.retry} /> : null}
        <ReactionFeedback state={state} review={() => reactions.review(playlist.id)} label="核对收藏状态" />
    </div>;
}

function Songs({ rows, title, empty, emptyAction, status, indexBase = 0, liked = false, onChanged, onPlay, onAdd, onOpen, onRemove, tools }: Playback & {
    rows: Row[]; title: string; empty: string; emptyAction?: React.ReactNode; status?: React.ReactNode;
    indexBase?: number; liked?: boolean; onChanged?: () => void;
    onRemove?: (song: NcmMeSong) => void;
    tools?: React.ReactNode;
}) {
    const section = useRef<HTMLElement>(null);
    const heading = useRef<HTMLHeadingElement>(null);
    const tracks = useMemo(() => {
        const occurrences = new Map<string, number>();
        return rows.map(row => {
            const track = toPrivateSong(row.song);
            const id = String(track.id);
            const occurrence = occurrences.get(id) ?? 0;
            occurrences.set(id, occurrence + 1);
            return { ...row, track, id, key: id + ':' + occurrence };
        });
    }, [rows]);
    const reactions = useLibraryReactions('like', (id, value) => {
        if (!liked || value) return;
        const active = document.activeElement?.closest<HTMLElement>('[data-halo-reaction-row]');
        if (section.current?.contains(active ?? null) && active?.dataset.haloReactionRow === id) {
            const siblings = [...section.current.querySelectorAll<HTMLElement>('[data-halo-reaction-row]')];
            const at = siblings.indexOf(active);
            const next = [...siblings.slice(at + 1), ...siblings.slice(0, at).reverse()]
                .find(row => row.dataset.haloReactionRow !== id);
            (next?.querySelector<HTMLButtonElement>('[data-halo-like]') ?? heading.current)?.focus();
        }
        onChanged?.();
    });
    const visible = liked ? tracks.filter(({ id }) => reactions.entries[id]?.value !== false) : tracks;
    const queue = visible.map(({ track }) => track);
    const ids = [...new Set(tracks.map(({ id }) => id))].join(',');
    const load = useCallback((signal: AbortSignal) =>
        libraryApi.checkLikes(ids.split(','), signal), [ids]);
    const read = useRead(!liked && ids ? load : null);
    const open = (type: 'artist' | 'album', name: string, id?: MediaId) => {
        if (id == null || !/^[1-9]\d{0,19}$/.test(String(id))) return;
        onOpen(toCollection({ name, id: String(id) }, type));
    };
    return <section ref={section} className="halo-library-section search-workspace halo-library-songs">
        <div className="halo-library-song-heading">
            <h2 ref={heading} tabIndex={-1}>{title}</h2>
            {(queue.length > 0 || tools) && <div className="halo-library-edit-actions">
                {queue.length > 0 && <button type="button" className="halo-gallery-action halo-gallery-play"
                    onClick={() => onPlay(queue[0], queue)} data-halo-library-play-page>
                    <Play size={17} aria-hidden="true" /> 播放本页
                </button>}
                {tools}
            </div>}
        </div>
        {status ?? (!queue.length && <LibraryEmpty message={empty} action={emptyAction} />)}
        {read.loading && <LibraryLoading text="正在读取红心状态…" />}
        {read.result?.error ? <PlaylistFeedback issue={playlistIssue(read.result.error, false, '红心')} onRetry={read.retry} /> : null}
        {visible.map(({ track, id, song, annotation, key }, index) => {
            const state = reactions.entries[id];
            const value = state?.value ?? (liked ? true : read.result?.value?.liked[id]);
            const busy = !!state?.pending || (value === undefined && read.loading);
            const disabled = busy || value === undefined || state?.needsReview;
            const heartLabel = state?.pending?.kind === 'write' ? (state.pending.next ? '正在点红心' : '正在取消红心')
                : busy ? '正在读取红心状态' : state?.needsReview || value === undefined ? '红心待核对'
                    : value ? '取消红心' : '点红心';
            const unavailable = isSongUnavailable(track);
            const items: MenuItem[] = [{
                id: 'queue', label: '加入播放队列', icon: ListPlus, disabled: unavailable, onSelect: () => onAdd(track),
            }];
            if (onRemove) {
                items.push({ id: 'remove', label: '从歌单移除', icon: Trash2, onSelect: () => onRemove(song) });
            } else if (canSaveHaloTrack(track) && !unavailable) {
                items.push({ id: 'save', label: '加入歌单', icon: Bookmark, onSelect: () => openAddSongsToPlaylist([track]) });
            }
            return <div key={key} className="halo-library-track-slot" data-halo-reaction-row={id}>
                <SearchResultRow index={index + indexBase} track={track} style={rowStyle}
                    annotation={annotation}
                    onPlayTrack={song => onPlay(song, queue)}
                    onOpenArtist={(_, name, entityId) => open('artist', name, entityId)}
                    onOpenAlbum={(_, name, entityId) => open('album', name, entityId)}
                    actions={<>
                        <button type="button" className="search-row-action halo-library-heart"
                            data-halo-like aria-pressed={state?.needsReview ? undefined : value}
                            aria-busy={busy || undefined} aria-disabled={disabled || undefined}
                            aria-label={'喜欢 ' + track.name} title={heartLabel}
                            onClick={() => {
                                if (!disabled && value !== undefined) reactions.toggle(id, value);
                            }}>
                            {state?.pending ? <LoaderCircle size={17} className="halo-library-spin" aria-hidden="true" />
                                : <Heart size={17} fill={value && !state?.needsReview ? 'currentColor' : 'none'} aria-hidden="true" />}
                        </button>
                        <MoreActions kind="track" label={'曲目操作 ' + track.name} items={items} />
                    </>}
                />
                <ReactionFeedback state={state} review={() => reactions.review(id)} label="核对红心状态" />
            </div>;
        })}
    </section>;
}

type Content =
    | { kind: 'playlists'; items: NcmMePlaylist[] }
    | { kind: 'playlist'; page: NcmMePlaylistTracks }
    | { kind: 'likes'; page: NcmMeSongPage }
    | { kind: 'recent'; records: NcmMeRecentRecords }
    | { kind: 'rank'; rows: Row[] };

async function readContent(target: LibraryTarget, offset: number, signal: AbortSignal): Promise<Content> {
    switch (target.tab) {
        case 'playlists':
            if (target.playlist) return { kind: 'playlist', page: await libraryApi.playlist(target.playlist, offset, pageSize, signal) };
            return { kind: 'playlists', items: (await libraryApi.playlists(signal)).items };
        case 'likes': return { kind: 'likes', page: await libraryApi.likes(offset, pageSize, signal) };
        case 'recent': return { kind: 'recent', records: await libraryApi.recent(signal) };
        case 'rank':
            return { kind: 'rank', rows: (await libraryApi.rank(target.range, signal)).items.map(row => ({
                song: row.song, annotation: row.playCount === undefined ? undefined : '播放 ' + row.playCount + ' 次',
            })) };
    }
}

function RecentLibrary({ records, navigate, ...playback }: Playback & {
    records: NcmMeRecentRecords; navigate: LibraryNavigate;
}) {
    const [checkingPlaylists, setCheckingPlaylists] = useState(false);
    const read = useRead(checkingPlaylists ? libraryApi.recent : null);
    const latest = read.result?.value ?? records;
    const issue: PlaylistIssue | undefined = read.result?.error
        ? playlistIssue(read.result.error)
        : latest.playlistsUnavailable ? { message: '最近播放的歌单暂时无法读取。' } : undefined;
    return <>
        <Songs {...playback} title="最近播放的歌曲" empty="暂无网易云播放记录"
            rows={records.songs.map(record => ({ song: record.song, annotation: whenPlayed(record.playedAt) }))} />
        <Playlists title="最近播放的歌单" items={latest.playlists} navigate={navigate} empty="暂无最近播放的歌单"
            status={read.loading ? <LibraryLoading text="正在重新读取歌单…" />
                : issue && <PlaylistFeedback issue={issue} onRetry={() => {
                    setCheckingPlaylists(true);
                    read.retry();
                }} />} />
    </>;
}

function loadingLabel(target: LibraryTarget) {
    switch (target.tab) {
        case 'playlists': return target.playlist ? '正在读取曲目…' : '正在读取歌单…';
        case 'likes': return '正在读取喜欢的歌…';
        case 'recent': return '正在读取播放记录…';
        case 'rank': return '正在读取听歌排行…';
    }
}

function LibraryContent({ target, onNavigate, isDaylight, ...playback }: Playback & {
    target: LibraryTarget; onNavigate: LibraryNavigate; isDaylight: boolean;
}) {
    const [offset, setOffset] = useState(0);
    const [edit, setEdit] = useState<PlaylistEdit>();
    const read = useCallback((signal: AbortSignal) => readContent(target, offset, signal), [offset, target]);
    const { result, retry, loading } = useRead(read, target.tab === 'likes' ? 'transient-error' : undefined);
    const content = result?.value;
    const total = content?.kind === 'playlist' || content?.kind === 'likes' ? content.page.total : undefined;
    const validOffset = total === undefined || offset < total || offset === 0;
    useEffect(() => {
        if (!validOffset && total !== undefined) setOffset(pageSize * Math.floor(Math.max(0, total - 1) / pageSize));
    }, [validOffset, total]);
    useEffect(() => {
        if (target.tab !== 'playlists') return;
        const onChanged = (event: Event) => {
            if (!target.playlist || target.playlist === (event as CustomEvent<string>).detail) retry();
        };
        playlistTrackChanges.addEventListener('change', onChanged);
        return () => playlistTrackChanges.removeEventListener('change', onChanged);
    }, [target, retry]);
    if (!content && result?.error) return <ErrorNotice error={result.error} retry={retry} />;
    if (!content || !validOffset) return <LibraryLoading text={loadingLabel(target)} rows={3} />;
    const review = () => {
        setEdit(undefined);
        if (edit?.kind === 'delete') onNavigate({ tab: 'playlists' }, { replace: true });
        else retry();
    };
    const editor = edit && <HaloPlaylistEditor edit={edit} isDaylight={isDaylight}
        onClose={() => setEdit(undefined)}
        onReview={review}
        onCreated={playlist => {
            setEdit(undefined);
            onNavigate({ tab: 'playlists', playlist: playlist.id });
        }}
        onSaved={review} />;
    if (content.kind === 'playlists') {
        const items = content.items.filter(playlist => !playlist.likedSongs).map(playlist => ({ playlist }));
        return <>
            <Playlists title="我创建的" items={items.filter(item => item.playlist.own)} navigate={onNavigate}
                empty="还没有创建歌单"
                action={<button type="button" className="halo-gallery-action halo-gallery-play" data-halo-new-playlist
                    onClick={() => setEdit({ kind: 'create' })}><Plus size={17} aria-hidden="true" />新建歌单</button>} />
            <Playlists title="我收藏的" items={items.filter(item => !item.playlist.own)} navigate={onNavigate}
                empty="还没有收藏歌单" />
            {editor}
        </>;
    }
    if (content.kind === 'recent') return <RecentLibrary {...playback} records={content.records} navigate={onNavigate} />;
    if (content.kind === 'rank') return <Songs {...playback} title="排行曲目" rows={content.rows}
        empty={target.tab === 'rank' && target.range === 'week' ? '本周暂无听歌排行' : '暂无听歌排行'} />;
    const playlist = content.kind === 'playlist' ? content.page.playlist : null;
    const editable = playlist?.own && !playlist.likedSongs ? playlist : null;
    const refreshIssue = result?.error ? playlistIssue(result.error) : undefined;
    const likeStatus = content.kind === 'likes' && loading ? <LibraryLoading text="正在更新喜欢的歌…" />
        : refreshIssue && <PlaylistFeedback
            issue={refreshIssue.status === 429 ? refreshIssue : { message: '暂时无法更新喜欢的歌。' }}
            onRetry={retry} />;
    return <>
        {playlist && <div className="halo-library-breadcrumb">
            <LibraryLink to={{ tab: 'playlists' }} navigate={onNavigate} className="halo-library-back">
                <ArrowLeft size={17} aria-hidden="true" /> 返回歌单
            </LibraryLink>
        </div>}
        <Songs {...playback} title={playlist?.name ?? '喜欢的歌曲'} rows={content.page.items.map(song => ({ song }))}
            empty={content.kind === 'likes' ? '还没有喜欢的歌' : '这份歌单还没有曲目'}
            emptyAction={content.kind === 'likes' ? <LibraryLink to={{ tab: 'playlists' }} navigate={onNavigate}
                className="halo-gallery-action">查看我的歌单</LibraryLink> : undefined}
            liked={content.kind === 'likes'} status={likeStatus} indexBase={offset} onChanged={retry}
            onRemove={editable ? song => setEdit({ kind: 'remove', playlist: editable, song }) : undefined}
            tools={editable ? <>
                <button type="button" className="halo-gallery-action" data-halo-add-tracks
                    onClick={() => setEdit({ kind: 'add', playlist: editable, present: content.page.items.map(song => song.id) })}>
                    <ListPlus size={17} aria-hidden="true" />添加曲目
                </button>
                <MoreActions kind="playlist" label="歌单操作" items={[{
                    id: 'delete', label: '删除歌单', icon: Trash2,
                    onSelect: () => setEdit({ kind: 'delete', playlist: editable }),
                }]} />
            </> : playlist && !playlist.own ? <Subscription playlist={playlist} /> : undefined} />
        {total !== undefined && total > pageSize && <nav className="halo-library-pagination" aria-label="曲目分页">
            <button type="button" className="halo-gallery-action" disabled={offset === 0}
                onClick={() => setOffset(offset - pageSize)}>上一页</button>
            <span>{offset + 1}–{Math.min(total, offset + pageSize)} / {total}</span>
            <button type="button" className="halo-gallery-action" disabled={offset + pageSize >= total}
                onClick={() => setOffset(offset + pageSize)}>下一页</button>
        </nav>}
        {editor}
    </>;
}

export default function HaloLibrary({ path, isDaylight, onNavigate, onHome, onSearch, ...playback }: Props) {
    const { theme } = useSettingsKitTheme();
    const reducedMotion = useKitReducedMotion();
    const target = useMemo(() => libraryTarget(path), [path]);
    const scroll = useRef<HTMLDivElement>(null);
    const { state, retry } = useHaloAccount();
    const account = state.kind === 'account' ? state.account : undefined;
    const bound = account?.bound === true && account.readable !== false;
    useEffect(() => { scroll.current?.scrollTo({ top: 0 }); }, [path]);
    return <div ref={scroll} className="halo-gallery st-window" data-halo-entry="library"
        style={{ ...EXPLORE_TOKENS, ...settingsTokens(isDaylight, theme), ...kitMotionTokens(reducedMotion) }}>
        <div className="halo-gallery-inner">
            <HaloMusicHeader onHome={onHome} onSearch={onSearch} onLibrary={onNavigate} libraryActive />
            <main className="halo-library">
                <div className="halo-library-heading">
                    <h1>个人曲库</h1>
                </div>
                {state.kind === 'loading' && <LibraryLoading text="正在检查账号绑定…" />}
                {state.kind === 'guest' && <LibraryLoginNotice />}
                {state.kind === 'error' && <ErrorNotice error={state.error} retry={retry} />}
                {account && !bound && <div className="halo-library-state" role="status">
                    <h2>{account.bound ? '需要重新绑定网易云' : '尚未绑定网易云'}</h2>
                    <p>请在音乐首页或播放器的「音质」面板{account.bound ? '重新绑定网易云' : '绑定网易云'}。</p>
                </div>}
                {bound && <>
                    <nav className="halo-library-tabs" aria-label="曲库分区">
                        {tabs.map(tab => <LibraryLink key={tab.target.tab} to={tab.target} navigate={onNavigate}
                            aria-current={target.tab === tab.target.tab ? 'page' : undefined}>{tab.label}</LibraryLink>)}
                    </nav>
                    {target.tab === 'rank' && <nav className="halo-library-range" aria-label="排行时段">
                        <LibraryLink to={{ tab: 'rank', range: 'week' }} navigate={onNavigate}
                            aria-current={target.range === 'week' ? 'page' : undefined}>本周</LibraryLink>
                        <LibraryLink to={{ tab: 'rank', range: 'all' }} navigate={onNavigate}
                            aria-current={target.range === 'all' ? 'page' : undefined}>全部时间</LibraryLink>
                    </nav>}
                    <LibraryContent key={JSON.stringify([path, account.userId, account.boundAt, account.managedInConsole])}
                        target={target} isDaylight={isDaylight} onNavigate={onNavigate} {...playback} />
                </>}
            </main>
        </div>
    </div>;
}
