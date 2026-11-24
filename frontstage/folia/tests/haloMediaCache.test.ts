import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { appDatabase } from '../../../src/services/appDatabase';
import {
    audioCacheWriter, cancelPendingAudioCacheWrites, getCachedAudioBlob, setAudioCacheSource,
} from '../../../src/services/audioCache';
import { clearBrowserCacheByCategory, getBrowserCacheUsageByCategory } from '../../../src/services/repositories/cacheRepository';
import { useAudioSettingsStore } from '../../../src/stores/useAudioSettingsStore';

const key = 'audio_netease:42';
const url = 'https://media.example.test/song.mp3';
const blob = () => new Blob(['audio bytes'], { type: 'audio/mpeg' });
const toggle = (enabled: boolean) => useAudioSettingsStore.getState().handleToggleMediaCache(enabled);
const authorize = (scope = new AbortController(), level: 'high' | 'standard' = 'high') => {
    setAudioCacheSource(key, { url, signal: scope.signal, level });
    return scope;
};

beforeEach(async () => {
    toggle(true);
    setAudioCacheSource(key);
    await clearBrowserCacheByCategory('media');
});

describe('Halo 歌曲缓存', () => {
    it('保存本机文件，以真实文件大小和歌曲数统计并按分类清理', async () => {
        const file = blob();
        await appDatabase.metadata_cache.put({ key: 'audioLevel_netease:42', data: 'high', timestamp: 0 });
        authorize();
        expect(await audioCacheWriter(key, url)?.(file)).toBe(true);
        expect(await getBrowserCacheUsageByCategory()).toMatchObject({ media: file.size, mediaCount: 1 });
        await clearBrowserCacheByCategory('media');
        expect(await getCachedAudioBlob(key)).toBeNull();
        expect(await getBrowserCacheUsageByCategory()).toMatchObject({ media: 0, mediaCount: 0 });
    });

    it('只有当前授权且音质一致时可读，换绑立即废弃旧权限', async () => {
        const scope = authorize();
        expect(await audioCacheWriter(key, url)?.(blob())).toBe(true);
        expect((await getCachedAudioBlob(key, 'high'))?.size).toBe(blob().size);
        expect(audioCacheWriter(key, 'https://media.example.test/different.mp3')).toBeUndefined();
        scope.abort();
        expect(await getCachedAudioBlob(key)).toBeNull();
        expect(audioCacheWriter(key, url)).toBeUndefined();
        authorize(undefined, 'standard');
        expect(await getCachedAudioBlob(key)).toBeNull();
    });

    it('关闭开关或清理后，正在下载的旧文件不会写回', async () => {
        authorize();
        const pending = audioCacheWriter(key, url)!;
        toggle(false);
        toggle(true);
        expect(await pending(blob())).toBe(false);
        const beforeClear = audioCacheWriter(key, url)!;
        cancelPendingAudioCacheWrites();
        expect(await beforeClear(blob())).toBe(false);
        expect(await getBrowserCacheUsageByCategory()).toMatchObject({ mediaCount: 0 });
    });
});
