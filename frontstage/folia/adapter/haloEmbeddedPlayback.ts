import { useEffect, useRef } from 'react';
import {
    selectDisplayDuration, selectDisplayPlayerState, selectDisplaySong, usePlaybackStore,
} from '../stores/usePlaybackStore';
import { omni } from '../services/onlineMusic/omni';
import { PlayerState, type SongResult } from '../types';
import { toSong } from './haloApi';
import { useHaloTrackFeedback } from './haloProvider';
import { owningFoliaFrame, playbackTime, type FoliaPlayback, type PlaybackPosition, type PlaybackView } from './playbackSession';

interface FoliaActions {
    time(): number;
    toggle(): void;
    step(direction: -1 | 1): void;
    seek(time: number): void;
    play(song: SongResult, queue: SongResult[], time: number, autoplay: boolean): void;
    openLyrics(): void;
}

function playbackView(time: number): PlaybackView {
    const state = usePlaybackStore.getState();
    const song = selectDisplaySong(state);
    const feedback = song && useHaloTrackFeedback.getState().get(String(song.id));
    const note = feedback?.text ?? '';
    const trial = state.audioSrc && !state.transitionDisplay
        ? omni.isPreviewAudioSource(state.audioSrc) : feedback?.trial === true;
    const playing = selectDisplayPlayerState(state) === PlayerState.PLAYING;
    const loading = !state.transitionDisplay && state.isLoadingToPlay;
    const phase = loading ? 'loading' : playing ? 'playing' : note && !feedback?.trial ? 'error'
        : song ? 'paused' : 'idle';
    return {
        track: {
            id: song ? String(song.id) : '', title: song?.name ?? '网抑云',
            artist: song?.artists.map(artist => artist.name).join(' / ') ?? '', cover: song?.album?.coverUrl,
        },
        size: state.playQueue.length,
        index: Math.max(0, state.playQueue.findIndex(track => track.id === song?.id)),
        position: playbackTime(time), duration: playbackTime(selectDisplayDuration(state)),
        phase, trial, note: loading ? '正在取址…' : note, queueLoading: false,
    };
}

export function useHaloEmbeddedPlayback(actions: FoliaActions) {
    const current = useRef(actions);
    current.current = actions;
    useEffect(() => {
        const frame = owningFoliaFrame();
        if (!frame) return;
        const loadSelection = (position: PlaybackPosition, autoplay: boolean) => {
            const queue = position.queue.map(track => toSong({
                id: track.id, name: track.title, artistNames: track.artist, coverUrl: track.cover,
                durationMs: track.durationMs,
            }));
            const song = queue[position.index];
            if (song) current.current.play(song, queue, position.time, autoplay);
        };
        const engine: FoliaPlayback = {
            view: () => playbackView(current.current.time()),
            toggle: () => current.current.toggle(),
            step: direction => current.current.step(direction),
            seek: time => current.current.seek(time),
            play: position => loadSelection(position, true),
            openLyrics: selection => {
                current.current.openLyrics();
                const state = usePlaybackStore.getState();
                if (!selectDisplaySong(state) && !state.isLoadingToPlay) loadSelection(selection, false);
            },
            subscribe: change => {
                const stopPlayback = usePlaybackStore.subscribe(change);
                const stopFeedback = useHaloTrackFeedback.subscribe(change);
                const events = ['timeupdate', 'loadedmetadata', 'seeked'] as const;
                events.forEach(type => document.addEventListener(type, change, true));
                return () => {
                    stopPlayback();
                    stopFeedback();
                    events.forEach(type => document.removeEventListener(type, change, true));
                };
            },
        };
        frame.ncmPlayback = engine;
        frame.dispatchEvent(new Event('ncm:playback-ready'));
        return () => {
            if (frame.ncmPlayback === engine) delete frame.ncmPlayback;
        };
    }, []);
}
