import { useEffect, useRef, type RefObject } from 'react';
import { dropPlaysInFlight, makeSongCurrent } from '../components/app/playback/currentSong';
import { findPlaybackDecks } from '../components/hifi/useWebEngineYield';
import { clearPrefetchRuntime } from '../services/prefetchService';
import { PlayerState, type SongResult } from '../types';
import { useAudioSettingsStore } from '../stores/useAudioSettingsStore';
import { usePlaybackStore } from '../stores/usePlaybackStore';
import { haloSettings, isPrivateHaloSong, playbackPath, toSong, type HaloPlayMode } from './haloApi';
import { haloSessionChanges } from './HaloAccount';

const prefsKey = 'ncm-player:prefs';
const sessionKey = 'ncm-player:v1';
const tabKey = 'ncm-player:tab';
const maxTracks = 300;
const sevenDays = 7 * 24 * 3600_000;
const maxResumeAge = 60_000;
const validId = /^\d{1,20}$/;

function read(key: string, storage: 'localStorage' | 'sessionStorage' = 'localStorage') {
    try {
        return window[storage].getItem(key);
    } catch {
        return null;
    }
}

function write(key: string, value: string | null, storage: 'localStorage' | 'sessionStorage' = 'localStorage') {
    try {
        if (value === null) window[storage].removeItem(key);
        else window[storage].setItem(key, value);
    } catch {
        // 播放可在浏览器禁用存储或配额不足时继续。
    }
}

function decode<T>(key: string): T | undefined {
    try {
        return JSON.parse(read(key) ?? 'null') ?? undefined;
    } catch {
        return undefined;
    }
}

type Preferences = { volume?: number; muted?: boolean; mode?: HaloPlayMode };
const storedModes: readonly HaloPlayMode[] = ['loop', 'single', 'shuffle', 'order'];
const modeFromLoop = (mode: 'all' | 'off' | 'one'): HaloPlayMode =>
    mode === 'off' ? 'order' : mode === 'one' ? 'single' : 'loop';
const loopFromMode = (mode: HaloPlayMode) => mode === 'order' ? 'off' : mode === 'single' ? 'one' : 'all';
function savePreferences(changes: Preferences) {
    write(prefsKey, JSON.stringify({ ...decode<Preferences>(prefsKey), ...changes }));
}

export function applyHaloPlaybackPreferences() {
    const prefs = decode<Preferences>(prefsKey);
    const store = useAudioSettingsStore;
    const existing = store.getState();
    const legacyMode = read('player_loop_mode');
    const mode = prefs?.mode && storedModes.includes(prefs.mode) ? prefs.mode
        : legacyMode === 'one' || legacyMode === 'off' || legacyMode === 'all'
            ? modeFromLoop(legacyMode) : haloSettings.mode;
    const legacyVolume = Number.parseFloat(read('player_volume') ?? '');
    const preferredVolume = typeof prefs?.volume === 'number' && Number.isFinite(prefs.volume)
        ? prefs.volume : legacyVolume;
    const volume = Number.isFinite(preferredVolume) ? Math.min(1, Math.max(0, preferredVolume)) : haloSettings.volume;
    store.setState({
        volume,
        isMuted: typeof prefs?.muted === 'boolean' ? prefs.muted : existing.isMuted,
        loopMode: loopFromMode(mode),
        enableMediaCache: false,
    });
    store.subscribe((state, previous) => {
        const volumeChanged = state.volume !== previous.volume;
        const muteChanged = state.isMuted !== previous.isMuted;
        const modeChanged = state.loopMode !== previous.loopMode;
        if (!volumeChanged && !muteChanged && !modeChanged) return;
        const next: Preferences = {};
        if (volumeChanged) next.volume = state.volume;
        if (muteChanged) next.muted = state.isMuted;
        if (modeChanged) next.mode = modeFromLoop(state.loopMode);
        savePreferences(next);
    });
}

interface SharedTrack {
    id: string;
    src: string;
    title: string;
    artist: string;
    cover?: string;
    durationMs?: number;
}

interface SharedSession {
    v: 1;
    queue: SharedTrack[];
    index: number;
    time: number;
    duration: number;
    playing: boolean;
    collapsed: boolean;
    tab: string;
    at: number;
}

type SavedSession = SharedSession | { v: 1; closed: true; tab: string; at: number };

const tabId = read(tabKey, 'sessionStorage') || Math.random().toString(36).slice(2);
write(tabKey, tabId, 'sessionStorage');

function safeTime(value: number) {
    return Number.isFinite(value) ? Math.max(0, value) : 0;
}

function isNeteaseSnapshotTrack(track: SharedTrack) {
    if (!track || typeof track.id !== 'string' || !validId.test(track.id) || typeof track.src !== 'string') return false;
    try {
        const source = new URL(track.src, location.origin);
        return source.origin === location.origin && source.pathname === '/music/play/' + track.id;
    } catch {
        return false;
    }
}

export function readHaloPlaybackSession() {
    const saved = decode<SavedSession>(sessionKey);
    if (saved?.v !== 1) return null;
    if ('closed' in saved) return 'closed' as const;
    if (!Array.isArray(saved.queue) || !Number.isFinite(saved.at)
        || Date.now() - saved.at > sevenDays) return null;
    const selectedId = saved.queue[saved.index]?.id;
    const queue = saved.queue.slice(0, maxTracks).filter(isNeteaseSnapshotTrack).map(track => toSong({
        id: track.id,
        name: String(track.title ?? ''),
        artistNames: String(track.artist ?? ''),
        coverUrl: typeof track.cover === 'string' && /^https?:\/\//.test(track.cover) ? track.cover : undefined,
        durationMs: safeTime(track.durationMs ?? (track.id === selectedId ? saved.duration * 1000 : 0)),
    }));
    const song = queue.find(item => item.id === selectedId);
    if (!song) return null;
    return {
        song, queue,
        time: safeTime(saved.time),
        autoplay: haloSettings.resume && saved.playing === true && saved.tab === tabId
            && Date.now() - saved.at < maxResumeAge,
    };
}

function snapshotTrack(song: SongResult) {
    if (isPrivateHaloSong(song)) return null;
    const { sourceRef } = song;
    if (sourceRef?.kind !== 'online' || sourceRef.providerId !== 'netease' || !validId.test(sourceRef.mediaId)) return null;
    return {
        id: sourceRef.mediaId,
        src: playbackPath(sourceRef.mediaId, useAudioSettingsStore.getState().audioQuality),
        title: song.name,
        artist: song.artists.map(artist => artist.name).join(' / '),
        cover: typeof song.album?.coverUrl === 'string' && /^https?:\/\//.test(song.album.coverUrl) ? song.album.coverUrl : '',
        durationMs: safeTime(song.durationMs),
    };
}

export function useHaloPlaybackSession(
    audioRef: RefObject<HTMLAudioElement | null>, pausePlayback: () => void, abortTransition: () => void,
) {
    const pause = useRef(pausePlayback);
    pause.current = pausePlayback;
    useEffect(() => {
        let channel: BroadcastChannel | null = null;
        try {
            if (typeof BroadcastChannel === 'function') channel = new BroadcastChannel('ncm-player');
        } catch {
            // 个别浏览器环境不提供跨标签页广播。
        }
        let ownsPlayback = true;
        let privateMarkerWritten = false;
        let lastWrittenAt = 0;
        const save = (force: boolean) => {
            const media = audioRef.current;
            if (!ownsPlayback || !media || media.readyState < 1) return;
            const now = Date.now();
            if (!force && now - lastWrittenAt < 2000) return;
            const { currentSong, playQueue } = usePlaybackStore.getState();
            if (currentSong && isPrivateHaloSong(currentSong)) {
                // 私人队列随当前文档播放，跨文档只保存无曲目内容的会话标记。
                if (!privateMarkerWritten) {
                    write(sessionKey, JSON.stringify({ v: 1, closed: true, tab: tabId, at: now }));
                    privateMarkerWritten = true;
                    lastWrittenAt = now;
                }
                return;
            }
            privateMarkerWritten = false;
            const selected = currentSong && snapshotTrack(currentSong);
            if (!selected) return;
            let queue = playQueue.slice(0, maxTracks).flatMap(track => {
                const stored = snapshotTrack(track);
                return stored ? [stored] : [];
            });
            let index = queue.findIndex(track => track.id === selected.id);
            if (index < 0) {
                queue = [selected, ...queue].slice(0, maxTracks);
                index = 0;
            }
            const value: SharedSession = {
                v: 1, queue, index, time: safeTime(media.currentTime), duration: safeTime(media.duration),
                playing: !media.paused && !media.ended, collapsed: true, tab: tabId, at: now,
            };
            write(sessionKey, JSON.stringify(value));
            lastWrittenAt = now;
        };
        if (channel) channel.onmessage = event => {
            if (event.data?.t === 'play' && event.data.tab !== tabId) {
                ownsPlayback = false;
                if (audioRef.current && !audioRef.current.paused) pause.current();
            }
        };
        const onMedia = (event: Event) => {
            if (event.target !== audioRef.current) return;
            if (event.type === 'playing') {
                ownsPlayback = true;
                channel?.postMessage({ t: 'play', tab: tabId });
            }
            save(event.type !== 'timeupdate');
        };
        const onIdentityChange = () => {
            dropPlaysInFlight();
            clearPrefetchRuntime();
            const { currentSong, playQueue, transitionDisplay, setPlayQueue } = usePlaybackStore.getState();
            const queue = playQueue.filter(song => !isPrivateHaloSong(song));
            const privateCurrent = currentSong && isPrivateHaloSong(currentSong);
            const transitionSong = transitionDisplay?.song;
            const privateTransition = transitionSong && isPrivateHaloSong(transitionSong);
            if (privateCurrent || privateTransition) abortTransition();
            if (privateCurrent) {
                pause.current();
                for (const deck of findPlaybackDecks(audioRef.current)) {
                    deck.pause();
                    deck.removeAttribute('src');
                    deck.load();
                }
                makeSongCurrent(null);
                usePlaybackStore.setState({
                    playQueue: queue, audioSrc: null, lyrics: null,
                    cachedCoverUrl: null, duration: 0, currentLineIndex: -1, handoffLineIndex: -1,
                    playerState: PlayerState.IDLE, isLoadingToPlay: false, transitionDisplay: null,
                    activePlaybackContext: 'main',
                });
            } else {
                if (queue.length !== playQueue.length) setPlayQueue(queue);
                if (privateTransition) usePlaybackStore.setState({ transitionDisplay: null });
            }
        };
        const onHide = () => save(true);
        const onVisibility = () => { if (document.visibilityState === 'hidden') onHide(); };
        const onShow = (event: PageTransitionEvent) => {
            if (!event.persisted) return;
            // 同一标签页在另一文档上写过进度时，用它重新建立本页的会话。
            const saved = decode<SavedSession>(sessionKey);
            if (saved?.tab === tabId && saved.at > lastWrittenAt) location.reload();
        };
        const events = ['playing', 'pause', 'seeked', 'timeupdate'] as const;
        events.forEach(type => document.addEventListener(type, onMedia, true));
        document.addEventListener('visibilitychange', onVisibility);
        window.addEventListener('pagehide', onHide);
        window.addEventListener('pageshow', onShow);
        haloSessionChanges.addEventListener('identitychange', onIdentityChange);
        return () => {
            events.forEach(type => document.removeEventListener(type, onMedia, true));
            document.removeEventListener('visibilitychange', onVisibility);
            window.removeEventListener('pagehide', onHide);
            window.removeEventListener('pageshow', onShow);
            haloSessionChanges.removeEventListener('identitychange', onIdentityChange);
            channel?.close();
        };
    }, [audioRef, abortTransition]);
}
