import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    canResumePlayback, forgetPlayback, readPlaybackSession, readResumablePlaybackSession, rememberPlayback,
    resumeTimeForMedia,
} from '../../../src/halo/playbackSession';

const key = 'ncm-player:v1';
const track = { id: '42', title: '公开曲目', artist: '歌手' };

beforeEach(() => {
    const storage = new Map<string, string>();
    vi.stubGlobal('window', { localStorage: { getItem: (key: string) => storage.get(key),
        setItem: (key: string, value: string) => storage.set(key, value) } });
    vi.stubGlobal('location', { origin: 'https://blog.example' });
});

afterEach(() => vi.unstubAllGlobals());

describe('小播放器与 Folia 共用续播记录', () => {
    it('兼容已有记录并保留过滤后的当前曲目索引', () => {
        window.localStorage.setItem(key, JSON.stringify({
            v: 1, tab: 'another-tab', at: Date.now(), index: 1, time: 31, duration: 180, playing: true,
            queue: [{ ...track, id: '41', src: 'https://other.example/music/play/41' },
                { ...track, src: '/music/play/42?level=hires' }],
        }));
        const session = readPlaybackSession();
        expect(session).toMatchObject({ index: 0, time: 31, queue: [track] });
        expect(session && !('closed' in session) && canResumePlayback(session)).toBe(false);
    });

    it('限制队列长度并保留正在播放的曲目和进度', () => {
        const queue = Array.from({ length: 310 }, (_, i) => ({ ...track, id: String(i + 1) }));
        rememberPlayback({ queue, index: 309, time: 12, duration: 140, playing: true });
        const session = readPlaybackSession();
        if (!session || 'closed' in session) throw new Error('续播记录缺失');
        expect(session.queue).toHaveLength(300);
        expect(session.queue[session.index].id).toBe('310');
        expect(session.time).toBe(12);
        expect(canResumePlayback(session)).toBe(true);
    });

    it('播放私人曲目时只保留结束标记', () => {
        rememberPlayback({ queue: [track], index: 0, time: 12, duration: 140, playing: false });
        forgetPlayback();
        expect(readPlaybackSession()).toMatchObject({ closed: true });
        expect(window.localStorage.getItem(key)).not.toContain('queue');
    });

    it('首页仅自动接续同一标签页近期仍在播放的会话', () => {
        const position = { queue: [track], index: 0, time: 42, duration: 140 };
        rememberPlayback({ ...position, playing: false });
        expect(readResumablePlaybackSession()).toBeNull();
        rememberPlayback({ ...position, playing: true });
        expect(readResumablePlaybackSession()).toMatchObject({ ...position, playing: true });
        const session = JSON.parse(window.localStorage.getItem(key)!);
        window.localStorage.setItem(key, JSON.stringify({ ...session, tab: 'another-tab' }));
        expect(readResumablePlaybackSession()).toBeNull();
        window.localStorage.setItem(key, JSON.stringify({ ...session, at: Date.now() - 61_000 }));
        expect(readResumablePlaybackSession()).toBeNull();
        forgetPlayback();
        expect(readResumablePlaybackSession()).toBeNull();
    });

    it('完整版进度超出试听时从片段开头播放，保留仍可播放的进度', () => {
        expect(resumeTimeForMedia(154.3, 30, true)).toBe(0);
        expect(resumeTimeForMedia(30, 30, true)).toBe(0);
        expect(resumeTimeForMedia(12, 30, true)).toBe(12);
        expect(resumeTimeForMedia(154.3, 286.667, false)).toBe(154.3);
    });
});
