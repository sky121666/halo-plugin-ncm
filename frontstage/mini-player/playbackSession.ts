export interface PlaybackTrack {
    id: string;
    title: string;
    artist: string;
    cover?: string;
    durationMs?: number;
}

export interface PlaybackPosition {
    queue: PlaybackTrack[];
    index: number;
    time: number;
    duration: number;
    playing: boolean;
}

export interface PlaybackView {
    track: PlaybackTrack;
    size: number;
    index: number;
    position: number;
    duration: number;
    phase: 'idle' | 'paused' | 'loading' | 'playing' | 'error';
    trial: boolean;
    note: string;
    queueLoading: boolean;
}

export interface MiniPlayback {
    view(): PlaybackView;
    loadQueue(): void;
    toggle(): void;
    step(direction: -1 | 1): void;
    seek(position: number): void;
}

export interface FoliaPlayback extends Omit<MiniPlayback, 'loadQueue'> {
    play(position: PlaybackPosition): void;
    openLyrics(selection: PlaybackPosition): void;
    subscribe(change: () => void): () => void;
}

export type FoliaFrame = HTMLIFrameElement & {
    ncmPlayback?: FoliaPlayback;
    ncmReturnToBlog?: () => void;
};

export function owningFoliaFrame(): FoliaFrame | undefined {
    const frame = window.frameElement;
    if (frame?.matches('iframe[data-ncm-folia-frame]')) return frame as FoliaFrame;
}

type PlaybackStamp = { tab: string; at: number };
export type PlaybackSession = PlaybackPosition & PlaybackStamp;

const sessionKey = 'ncm-player:v1';
const tabKey = 'ncm-player:tab';
const retention = 7 * 24 * 3600_000;
const resumeWindow = 60_000;
const maxTracks = 300;

export const musicLyricsPath = '/music?view=lyrics';

export const isPlaybackId = (id: unknown): id is string => typeof id === 'string' && /^[1-9]\d{0,19}$/.test(id);
export const playbackTime = (time: unknown) => typeof time === 'number' && Number.isFinite(time) ? Math.max(0, time) : 0;

export function resumeTimeForMedia(time: number, duration: number, trial: boolean) {
    const position = playbackTime(time);
    if (!Number.isFinite(duration) || duration <= 0) return position;
    if (trial) return position < duration ? position : 0;
    return Math.min(position, Math.max(0, duration - 0.25));
}

function read(storage: 'localStorage' | 'sessionStorage', key: string) {
    try { return window[storage].getItem(key); } catch { return null; }
}

function write(storage: 'localStorage' | 'sessionStorage', key: string, value: string) {
    try { window[storage].setItem(key, value); } catch { /* Playback also works without storage. */ }
}

const tab = read('sessionStorage', tabKey) || Math.random().toString(36).slice(2);
write('sessionStorage', tabKey, tab);

function safeTrack(value: unknown): PlaybackTrack | null {
    if (!value || typeof value !== 'object' || !('id' in value) || !isPlaybackId(value.id)) return null;
    const track = value as PlaybackTrack & { src?: unknown };
    if (track.src !== undefined) {
        try {
            if (typeof track.src !== 'string') return null;
            const source = new URL(track.src, location.origin);
            if (source.origin !== location.origin || source.pathname !== '/music/play/' + track.id) return null;
        } catch { return null; }
    }
    const cover = typeof track.cover === 'string' && /^https?:\/\//.test(track.cover) ? track.cover : undefined;
    return {
        id: track.id,
        title: typeof track.title === 'string' ? track.title.slice(0, 500) : '未知歌曲',
        artist: typeof track.artist === 'string' ? track.artist.slice(0, 500) : '',
        cover,
        durationMs: playbackTime(track.durationMs),
    };
}

function normalize(position: PlaybackPosition): PlaybackPosition | null {
    if (!Array.isArray(position.queue) || !Number.isInteger(position.index)) return null;
    const selected = safeTrack(position.queue[position.index]);
    if (!selected) return null;
    const queue: PlaybackTrack[] = [];
    let index = -1;
    for (const [originalIndex, item] of position.queue.slice(0, maxTracks).entries()) {
        const track = safeTrack(item);
        if (!track) continue;
        if (originalIndex === position.index) index = queue.length;
        queue.push(track);
    }
    if (index === -1) {
        queue.splice(maxTracks - 1, 1, selected);
        index = queue.length - 1;
    }
    return {
        queue,
        index,
        time: playbackTime(position.time),
        duration: playbackTime(position.duration),
        playing: position.playing === true,
    };
}

export function readPlaybackSession(): PlaybackSession | (PlaybackStamp & { closed: true }) | null {
    try {
        const saved = JSON.parse(read('localStorage', sessionKey) ?? 'null');
        if (saved?.v !== 1) return null;
        if (!Number.isFinite(saved.at) || Date.now() - saved.at > retention || typeof saved.tab !== 'string') return null;
        if (saved.closed === true) return { closed: true, tab: saved.tab, at: saved.at };
        const position = normalize(saved);
        return position ? { ...position, tab: saved.tab, at: saved.at } : null;
    } catch { return null; }
}

export const isNewerPlaybackSession = (session: PlaybackStamp, lastWrite: number) =>
    session.tab === tab && session.at > lastWrite;

export const canResumePlayback = (session: PlaybackSession) =>
    session.tab === tab && session.playing && Date.now() - session.at < resumeWindow;

export function readResumablePlaybackSession(): PlaybackSession | null {
    const saved = readPlaybackSession();
    return saved && !('closed' in saved) && canResumePlayback(saved) ? saved : null;
}

export function rememberPlayback(position: PlaybackPosition) {
    const state = normalize(position);
    if (!state) return 0;
    const at = Date.now();
    // `src` stays a relative playback endpoint for compatibility with existing Folia sessions.
    const queue = state.queue.map(track => ({...track, src: '/music/play/' + track.id}));
    write('localStorage', sessionKey, JSON.stringify({...state, queue, v: 1, tab, at}));
    return at;
}

export function forgetPlayback() {
    write('localStorage', sessionKey, JSON.stringify({v: 1, closed: true, tab, at: Date.now()}));
}

export function playbackOwnership(onOtherPlay: () => void) {
    const owner = Math.random().toString(36).slice(2);
    let channel: BroadcastChannel | undefined;
    try { channel = new BroadcastChannel('ncm-player'); } catch { /* Older browsers can play locally. */ }
    if (channel) channel.onmessage = ({data}) => {
        if (data?.t === 'play' && (data.owner ? data.owner !== owner : data.tab !== tab)) onOtherPlay();
    };
    return {
        claim: () => { channel?.postMessage({t: 'play', tab, owner}); },
        close: () => { channel?.close(); },
    };
}
