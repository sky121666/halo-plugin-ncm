import { useEffect, useRef, type RefObject } from 'react';
import { dropPlaysInFlight, makeSongCurrent } from '../components/app/playback/currentSong';
import { findPlaybackDecks } from '../components/hifi/useWebEngineYield';
import { clearPrefetchRuntime } from '../services/prefetchService';
import { PlayerState, type SongResult } from '../types';
import { useAudioSettingsStore } from '../stores/useAudioSettingsStore';
import { usePlaybackStore } from '../stores/usePlaybackStore';
import { haloSettings, haloTrackId, isPrivateHaloSong, toSong } from './haloApi';
import { haloSessionChanges } from './HaloAccount';
import {
    canResumePlayback, forgetPlayback, isNewerPlaybackSession, playbackOwnership,
    owningFoliaFrame, playbackTime, readPlaybackSession, rememberPlayback, type PlaybackTrack,
} from './playbackSession';

const prefsKey = 'ncm-player:prefs';

function read(key: string) {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
}

function decode<T>(key: string): T | undefined {
    try {
        return JSON.parse(read(key) ?? 'null') ?? undefined;
    } catch {
        return undefined;
    }
}

type LegacyPlaybackPreferences = { volume?: number; muted?: boolean; mode?: 'loop' | 'order' | 'single' | 'shuffle' };

/** 仅在配置同步初始化前恢复原浏览器的播放设置。 */
export function applyHaloPlaybackPreferences() {
    const prefs = decode<LegacyPlaybackPreferences>(prefsKey);
    const store = useAudioSettingsStore;
    const native = store.getState();
    const mode = prefs?.mode;
    const volume = prefs?.volume;
    store.setState({
        volume: typeof volume === 'number' && Number.isFinite(volume) ? Math.min(1, Math.max(0, volume))
            : read('player_volume') === null ? haloSettings.volume : native.volume,
        loopMode: mode === 'order' ? 'off' : mode === 'single' ? 'one' : mode === 'loop' || mode === 'shuffle' ? 'all'
            : read('player_loop_mode') !== null ? native.loopMode
                : haloSettings.mode === 'single' ? 'one' : haloSettings.mode === 'order' ? 'off' : 'all',
        isMuted: typeof prefs?.muted === 'boolean' ? prefs.muted : native.isMuted,
    });
}

export function readHaloPlaybackSession() {
    const saved = readPlaybackSession();
    if (!saved) return null;
    if ('closed' in saved) return 'closed' as const;
    const queue = saved.queue.map((track, index) => toSong({
        id: track.id, name: track.title, artistNames: track.artist, coverUrl: track.cover,
        durationMs: track.durationMs || (index === saved.index ? saved.duration * 1000 : 0),
    }));
    return {
        song: queue[saved.index], queue, time: saved.time,
        autoplay: !owningFoliaFrame() && haloSettings.resume && canResumePlayback(saved),
    };
}

function snapshotTrack(song: SongResult): PlaybackTrack | null {
    const id = haloTrackId(song);
    if (!id || isPrivateHaloSong(song)) return null;
    return {
        id,
        title: song.name,
        artist: song.artists.map(artist => artist.name).join(' / '),
        cover: song.album?.coverUrl,
        durationMs: playbackTime(song.durationMs),
    };
}

export function useHaloPlaybackSession(
    audioRef: RefObject<HTMLAudioElement | null>, pausePlayback: () => void, abortTransition: () => void,
) {
    const pause = useRef(pausePlayback);
    pause.current = pausePlayback;
    useEffect(() => {
        // Embedding can restore a paused selection before the parent makes a resume request.
        // Acquire ownership on actual playback so that passive restore cannot clear that intent.
        let ownsPlayback = !owningFoliaFrame();
        let departing = false;
        let privateMarkerWritten = false;
        let lastWrittenAt = 0;
        const ownership = playbackOwnership(() => {
            ownsPlayback = false;
            if (audioRef.current && !audioRef.current.paused) pause.current();
        });
        const save = (force: boolean) => {
            const media = audioRef.current;
            if (!ownsPlayback || departing) return;
            const now = Date.now();
            if (!force && now - lastWrittenAt < 2000) return;
            const { currentSong, playQueue } = usePlaybackStore.getState();
            if (currentSong && isPrivateHaloSong(currentSong)) {
                // 私人队列随当前文档播放，跨文档只保存无曲目内容的会话标记。
                if (!privateMarkerWritten) {
                    forgetPlayback();
                    privateMarkerWritten = true;
                    lastWrittenAt = now;
                }
                return;
            }
            if (!media || media.readyState < 1) return;
            privateMarkerWritten = false;
            const selected = currentSong && snapshotTrack(currentSong);
            if (!selected) return;
            const queue = playQueue.map(snapshotTrack).filter(track => track !== null);
            let index = queue.findIndex(track => track.id === selected.id);
            if (index < 0) {
                queue.unshift(selected);
                index = 0;
            }
            lastWrittenAt = rememberPlayback({
                queue, index, time: media.currentTime, duration: media.duration, playing: !media.paused && !media.ended,
            });
        };
        const onMedia = (event: Event) => {
            if (event.target !== audioRef.current || departing) return;
            if (event.type === 'playing') {
                ownsPlayback = true;
                ownership.claim();
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
        const onHide = () => { save(true); departing = true; };
        const onVisibility = () => { if (document.visibilityState === 'hidden') save(true); };
        const onShow = (event: PageTransitionEvent) => {
            departing = false;
            if (!event.persisted) return;
            // 同一标签页在另一文档上写过进度时，用它重新建立本页的会话。
            const saved = readPlaybackSession();
            if (saved && isNewerPlaybackSession(saved, lastWrittenAt)) location.reload();
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
            ownership.close();
        };
    }, [audioRef, abortTransition]);
}
