import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Music2, Search, X } from 'lucide-react';
import ThemedDialog from '../components/shared/ThemedDialog';
import { SheetButton } from '../components/shared/Sheet';
import { usePlaybackStore } from '../stores/usePlaybackStore';
import { setStatusMessage } from '../stores/useStatusMessageStore';
import { getSongArtistLabel } from '../services/onlineMusic/songMetadata';
import type { SongResult } from '../types';
import type { NcmMePlaylist } from './generated/ncm-me-playlist';
import { haloGet, haloSettings, haloTrackId, musicSearch, toSong, type HaloSong } from './haloApi';
import { libraryApi } from './haloLibraryApi';
import { PlaylistFeedback, playlistIssue, type PlaylistIssue } from './PlaylistFeedback';
import './haloPlaylistEditor.css';

export type PlaylistEdit =
    | { kind: 'create' }
    | { kind: 'delete'; playlist: NcmMePlaylist }
    | { kind: 'add'; playlist: NcmMePlaylist; present: string[] }
    | { kind: 'remove'; playlist: NcmMePlaylist; song: { id: string; name?: string } };

type DialogControls = { isDaylight: boolean; onClose: () => void; onReview: () => void };

function useWrite() {
    const live = useRef(true);
    const running = useRef(false);
    const [busy, setBusy] = useState(false);
    const [issue, setIssue] = useState<PlaylistIssue>();
    const reviewRequired = issue?.action === 'review';
    useEffect(() => {
        live.current = true;
        return () => { live.current = false; };
    }, []);
    const run = async <T,>(save: () => Promise<T>, done: (value: T) => void) => {
        if (running.current || reviewRequired) return;
        running.current = true;
        setBusy(true);
        setIssue(undefined);
        let value: T;
        try {
            value = await save();
        } catch (failure) {
            if (live.current) {
                setIssue(playlistIssue(failure, true));
            }
            return;
        } finally {
            running.current = false;
            if (live.current) setBusy(false);
        }
        if (live.current) done(value);
    };
    return { busy, issue, reviewRequired, run };
}

function WriteError({ message }: { message: string }) {
    return message ? <p className="halo-playlist-error" role="alert">{message}</p> : null;
}

export function CreatePlaylistDialog({ isDaylight, onClose, onReview, onCreated }: DialogControls & {
    onCreated: (playlist: NcmMePlaylist) => void;
}) {
    const [name, setName] = useState('');
    const [isPrivate, setPrivate] = useState(false);
    const form = useRef<HTMLFormElement>(null);
    const { busy, issue, reviewRequired, run } = useWrite();
    const dismiss = reviewRequired ? onReview : onClose;
    const trimmed = name.trim();
    const valid = trimmed.length > 0 && [...trimmed].length <= 40 && !/[\u0000-\u001f\u007f-\u009f]/.test(trimmed);
    const submit = (event: FormEvent) => {
        event.preventDefault();
        if (!valid) return;
        void run(() => libraryApi.createPlaylist({ name: trimmed, privacy: isPrivate ? 10 : 0 }), playlist => {
            setStatusMessage({ type: 'success', text: '歌单已创建' });
            onCreated(playlist);
        });
    };
    return <ThemedDialog isOpen isDaylight={isDaylight} title="新建歌单" onClose={dismiss} closeDisabled={busy}
        footer={<>
            <SheetButton disabled={busy} onClick={dismiss} data-halo-playlist-button>取消</SheetButton>
            <SheetButton variant="primary" disabled={busy || reviewRequired || !valid} data-halo-playlist-button
                onClick={() => form.current?.requestSubmit()} data-halo-create-submit>
                {busy ? '正在创建…' : '创建'}
            </SheetButton>
        </>}>
        <form ref={form} className="halo-playlist-form" onSubmit={submit} data-halo-playlist-form>
            <label>
                <span>歌单名称</span>
                <input data-autofocus autoComplete="off" value={name} maxLength={80} required disabled={busy}
                    onChange={event => setName(event.target.value)} />
            </label>
            {trimmed && !valid && <WriteError message="请输入 1–40 个字，避免换行或控制字符。" />}
            <label className="halo-playlist-checkbox">
                <input type="checkbox" checked={isPrivate} disabled={busy} onChange={event => setPrivate(event.target.checked)} />
                仅自己可见
            </label>
            <PlaylistFeedback issue={issue} onReview={onReview} />
        </form>
    </ThemedDialog>;
}

function ConfirmPlaylistEdit({ edit, isDaylight, onClose, onReview, onSaved }: DialogControls & {
    edit: Extract<PlaylistEdit, { kind: 'delete' | 'remove' }>;
    onSaved: () => void;
}) {
    const { busy, issue, reviewRequired, run } = useWrite();
    const dismiss = reviewRequired ? onReview : onClose;
    const deleting = edit.kind === 'delete';
    const action = deleting ? '删除歌单' : '移除曲目';
    const playlist = edit.playlist;
    const label = playlist.name ?? '此歌单';
    const description = deleting ? '将从你的网易云账号删除「' + label + '」。'
        : '将「' + (edit.song.name ?? '这首歌') + '」从「' + label + '」移除。';
    const confirm = () => void run(async () => {
        if (edit.kind === 'delete') await libraryApi.deletePlaylist(playlist.id);
        else await libraryApi.removeTracks(playlist.id, [edit.song.id]);
    }, () => {
        setStatusMessage({ type: 'success', text: deleting ? '歌单已删除' : '曲目已移除' });
        onSaved();
    });
    return <ThemedDialog isOpen isDaylight={isDaylight} title={action} onClose={dismiss}
        closeDisabled={busy} footer={<>
            <SheetButton data-autofocus disabled={busy} onClick={dismiss} data-halo-playlist-button>取消</SheetButton>
            <SheetButton variant="danger" disabled={busy || reviewRequired} onClick={confirm} data-halo-playlist-confirm data-halo-playlist-button>
                {busy ? '正在提交…' : action}
            </SheetButton>
        </>}>
        <p className="halo-playlist-description">{description}</p>
        <PlaylistFeedback issue={issue} onReview={onReview} />
    </ThemedDialog>;
}

type SearchPage = { query: string; nextPage: number; songs: SongResult[]; more: boolean };

function availableSongs(songs: SongResult[], present: Set<string>) {
    const seen = new Set(present);
    return songs.flatMap(song => {
        const id = haloTrackId(song);
        if (!id || seen.has(id)) return [];
        seen.add(id);
        return [{ id, song }];
    });
}

function AddTracksDialog({ playlist, present, isDaylight, onClose, onReview, onSaved }: DialogControls & {
    playlist: NcmMePlaylist; present: string[]; onSaved: () => void;
}) {
    const [queue] = useState(() => {
        const state = usePlaybackStore.getState();
        return state.currentSong ? [state.currentSong, ...state.playQueue] : state.playQueue;
    });
    const [query, setQuery] = useState('');
    const [results, setResults] = useState<SearchPage>();
    const [searching, setSearching] = useState(false);
    const [searchError, setSearchError] = useState('');
    const [selection, setSelection] = useState<Map<string, string>>(() => new Map());
    const activeRequest = useRef<AbortController | null>(null);
    const { busy, issue, reviewRequired, run } = useWrite();
    const dismiss = reviewRequired ? onReview : onClose;
    useEffect(() => () => activeRequest.current?.abort(), []);
    const options = availableSongs(results?.songs ?? queue, new Set(present));

    const search = async (text: string, previous?: SearchPage) => {
        if (!text) return;
        activeRequest.current?.abort();
        const controller = new AbortController();
        activeRequest.current = controller;
        const page = previous?.nextPage ?? 1;
        setSearching(true);
        setSearchError('');
        if (!previous) setResults({ query: text, nextPage: 1, songs: [], more: false });
        try {
            const data = await haloGet<{ items: HaloSong[]; total: number }>(musicSearch,
                { q: text, type: 'song', page }, controller.signal);
            if (controller.signal.aborted) return;
            const songs = [...(previous?.songs ?? []), ...data.items.map(toSong)];
            setResults({ query: text, nextPage: page + 1, songs, more: data.items.length > 0 && songs.length < data.total });
        } catch (failure) {
            if (!controller.signal.aborted) {
                setSearchError(failure instanceof Error ? failure.message : '无法搜索曲目，请重试。');
            }
        } finally {
            if (!controller.signal.aborted) setSearching(false);
        }
    };
    const save = () => {
        if (!selection.size) return;
        void run(() => libraryApi.addTracks(playlist.id, [...selection.keys()]), () => {
            setStatusMessage({ type: 'success', text: '已加入 ' + selection.size + ' 首曲目' });
            onSaved();
        });
    };
    return <ThemedDialog isOpen isDaylight={isDaylight} title="添加曲目" size="m" onClose={dismiss} closeDisabled={busy}
        footer={<>
            <SheetButton onClick={dismiss} disabled={busy} data-halo-playlist-button>取消</SheetButton>
            <SheetButton variant="primary" disabled={busy || reviewRequired || selection.size === 0}
                onClick={save} data-halo-track-submit data-halo-playlist-button>
                {busy ? '正在添加…' : selection.size ? '添加 ' + selection.size + ' 首' : '添加'}
            </SheetButton>
        </>}>
        <p className="halo-playlist-description">加入「{playlist.name ?? '此歌单'}」</p>
        <PlaylistFeedback issue={issue} onReview={onReview} />
        {haloSettings.search && <form className="halo-playlist-search" onSubmit={event => {
            event.preventDefault();
            if (query.trim()) void search(query.trim());
            else { activeRequest.current?.abort(); setResults(undefined); setSearchError(''); setSearching(false); }
        }}>
            <input data-autofocus type="search" aria-label="搜索曲目" placeholder="搜索曲目" maxLength={100}
                value={query} onChange={event => setQuery(event.target.value)} disabled={busy} />
            <button type="submit" disabled={busy} aria-label="搜索曲目"><Search size={17} aria-hidden="true" /></button>
        </form>}
        {selection.size > 0 && <details className="halo-playlist-selection">
            <summary>已选 {selection.size} 首</summary>
            <ul>{[...selection].map(([id, name]) => <li key={id}>
                <span>{name}</span>
                <button type="button" aria-label={'取消选择 ' + name} disabled={busy}
                    onClick={() => setSelection(current => {
                        const next = new Map(current);
                        next.delete(id);
                        return next;
                    })}><X size={16} aria-hidden="true" /></button>
            </li>)}</ul>
        </details>}
        {searchError && <div className="halo-playlist-error" role="status">
            {searchError} <button type="button" onClick={() => void search(results?.query ?? query.trim(), results?.songs.length ? results : undefined)}>重试</button>
        </div>}
        {searching && <p className="halo-playlist-note" role="status">正在搜索…</p>}
        {!results && options.length > 0 && <p className="halo-playlist-note">当前播放队列</p>}
        {options.length === 0 && !searching && !searchError && <p className="halo-playlist-note">
            {results ? '没有可添加的曲目' : haloSettings.search ? '搜索曲目后勾选添加。' : '可在曲库的曲目列表中使用「加入歌单」。'}
        </p>}
        {options.length > 0 && <ul className="halo-playlist-track-options">
            {options.map(({ id, song }) => <li key={id}>
                <label>
                    <input type="checkbox" checked={selection.has(id)} data-halo-track-option
                        disabled={busy || (selection.size >= 100 && !selection.has(id))}
                        onChange={event => {
                            const checked = event.target.checked;
                            setSelection(selected => {
                                const next = new Map(selected);
                                if (checked) next.set(id, song.name);
                                else next.delete(id);
                                return next;
                            });
                        }} />
                    <Music2 size={16} aria-hidden="true" />
                    <span><strong>{song.name}</strong><small>{getSongArtistLabel(song)}</small></span>
                </label>
            </li>)}
        </ul>}
        {results?.more && <button type="button" className="halo-playlist-more" disabled={busy || searching}
            onClick={() => void search(results.query, results)}>更多曲目</button>}
    </ThemedDialog>;
}

export function HaloPlaylistEditor({ edit, onCreated, onSaved, ...controls }: DialogControls & {
    edit: PlaylistEdit; onCreated: (playlist: NcmMePlaylist) => void; onSaved: () => void;
}) {
    switch (edit.kind) {
        case 'create': return <CreatePlaylistDialog {...controls} onCreated={onCreated} />;
        case 'add': return <AddTracksDialog {...controls} playlist={edit.playlist} present={edit.present} onSaved={onSaved} />;
        default: return <ConfirmPlaylistEdit {...controls} edit={edit} onSaved={onSaved} />;
    }
}
