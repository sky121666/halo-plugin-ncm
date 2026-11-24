// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { requestHaloMusicAi } from '../../../src/halo/haloMusicAi';
import { generateThemeFromLyrics } from '../../../src/services/aiText';
import { FALLBACK_AI_DUAL_THEME } from '../../../src/services/themeSanitizer';
import { getCachedThemeStateForSong, isCachedThemeForSource, saveCachedSongTheme } from '../../../src/services/themeCache';
import { createSongSyncFingerprintCandidates } from '../../../src/services/sync/syncFingerprint';
import { readThemeSyncRegistry } from '../../../src/services/sync/themeSyncRegistry';
import { getPlaybackSongKey } from '../../../src/utils/appPlaybackGuards';
import { getContrastRatio } from '../../../src/utils/themeColorMath';
import type { DualTheme, SongResult } from '../../../src/types';

const cache = vi.hoisted(() => new Map<string, unknown>());
vi.mock('../../../src/services/db', () => ({
    getFromCache: async (key: string) => cache.get(key) ?? null,
    saveToCache: async (key: string, value: unknown) => { cache.set(key, value); },
}));
vi.mock('../../../src/services/sync/themeSyncRegistry', () => ({ readThemeSyncRegistry: vi.fn(async () => ({})) }));
vi.mock('../../../src/halo/haloMusicAi', () => ({
    canUseHaloMusicAi: () => true,
    requestHaloMusicAi: vi.fn(),
}));

const song: SongResult = {
    id: '150560', name: '示例歌曲', artists: [], album: { id: '8', name: '专辑' }, durationMs: 60_000,
    sourceRef: { kind: 'online', providerId: 'netease', mediaId: '150560' },
};
const ai = FALLBACK_AI_DUAL_THEME;
const cover: DualTheme = {
    light: { ...ai.light, provider: 'Built-in' },
    dark: { ...ai.dark, provider: 'Built-in' },
};

beforeEach(() => {
    cache.clear();
    vi.mocked(readThemeSyncRegistry).mockReset().mockResolvedValue({});
    vi.mocked(requestHaloMusicAi).mockReset();
});

describe('Halo 本地单曲主题', () => {
    it('已有封面配色仍可生成 AI 主题，两类结果分别复用', async () => {
        const songKey = getPlaybackSongKey(song);
        cache.set('dual_theme_' + songKey, cover);
        const before = await getCachedThemeStateForSong(song, 'ai');
        expect(before.kind).toBe('cover');
        expect(isCachedThemeForSource(before, 'ai')).toBe(false);

        await saveCachedSongTheme(songKey, ai, 'ai');
        expect(cache.get('theme_cover_' + songKey)).toEqual(cover);
        await saveCachedSongTheme(songKey, cover, 'cover');
        expect((await getCachedThemeStateForSong(song, 'ai')).kind).toBe('dual');
        expect((await getCachedThemeStateForSong(song, 'cover')).kind).toBe('cover');
        expect(cache.get('dual_theme_' + songKey)).toEqual(ai);
    });

    it('能读回旧同步索引中的本地编辑主题', async () => {
        const [fingerprint] = createSongSyncFingerprintCandidates(song);
        const cacheKey = 'dual_theme_downloaded';
        vi.mocked(readThemeSyncRegistry).mockResolvedValue({
            [fingerprint]: { fingerprint, cacheKey, updatedAt: '2026-11-01T00:00:00Z', source: 'edited' },
        });
        cache.set(cacheKey, cover);
        const restored = await getCachedThemeStateForSong(song, 'ai');
        expect(restored.kind).toBe('dual');
        expect(isCachedThemeForSource(restored, 'ai')).toBe(true);
    });

    it('保留 Folia 的关键词和图标，并修复低对比度文字', async () => {
        const wordColors = [{ word: '海边', color: '#a8c5df' }];
        const lyricsIcons = ['Waves', 'Wind'];
        vi.mocked(requestHaloMusicAi).mockResolvedValue({
            light: { ...ai.light, backgroundColor: '#eeeeee', primaryColor: '#cccccc', secondaryColor: '#cccccc',
                description: '听潮水退去', wordColors, lyricsIcons },
            dark: { ...ai.dark, backgroundColor: '#282828', primaryColor: '#333333', secondaryColor: '#333333',
                description: '听潮水退去', wordColors, lyricsIcons },
        });
        const result = await generateThemeFromLyrics('海边');
        expect(requestHaloMusicAi).toHaveBeenCalledTimes(1);
        for (const theme of [result.light, result.dark]) {
            expect(theme.wordColors).toEqual(wordColors);
            expect(theme.lyricsIcons).toEqual(lyricsIcons);
            expect(theme.description).toBe('听潮水退去');
            expect(getContrastRatio(theme.primaryColor, theme.backgroundColor)).toBeGreaterThanOrEqual(4.5);
            expect(getContrastRatio(theme.secondaryColor, theme.backgroundColor)).toBeGreaterThanOrEqual(4.5);
        }
    });

    it('拒绝无效的明暗组合，由播放器保留当前配色', async () => {
        vi.mocked(requestHaloMusicAi).mockResolvedValue({ light: ai.dark, dark: ai.light });
        await expect(generateThemeFromLyrics('海边')).rejects.toThrow('明暗配色无效');
    });
});
