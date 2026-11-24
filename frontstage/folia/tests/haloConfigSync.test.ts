// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigRequestError, configApi, type MyConfig } from '../../../src/halo/haloConfigApi';
import { overlayConfig, selectConfig, type ConfigDocument } from '../../../src/halo/haloConfigDocument';
import { HaloConfigSync } from '../../../src/halo/haloConfigSync';

const revision = (number: number) => String(number).padStart(48, '0');
const playback = (volume: number): ConfigDocument => ({ playback: { volume, loopMode: 'all' } });
const config = (profile: string, volume: number | null, rev = 0): MyConfig => ({
    profileKey: profile,
    snapshot: { schemaVersion: 1, revision: revision(rev), config: volume === null ? null : playback(volume) },
    canPublishSite: false,
    canViewDiagnostics: false,
});
const advance = (ms = 100) => vi.advanceTimersByTimeAsync(ms);

function fixture(initial = 0.8) {
    const service = new HaloConfigSync();
    let device = playback(initial);
    let onChange = () => {};
    const detach = service.attach({
        capture: () => device,
        async restore(next) {
            device = overlayConfig(device, next);
            onChange();
        },
        subscribe(change) {
            onChange = change;
            return () => { onChange = () => {}; };
        },
    });
    return {
        service,
        close() { detach(); service.dispose(); },
        volume: () => device.playback.volume,
        loop: () => device.playback.loopMode,
        mediaCache: () => device.playback.enableMediaCache,
        changeMediaCache(enableMediaCache: boolean) {
            device = overlayConfig(device, { playback: { enableMediaCache } });
            onChange();
        },
        changeLoop(loopMode: 'off' | 'all' | 'one') {
            device = overlayConfig(device, { playback: { loopMode } });
            onChange();
        },
        changeEqualizer() {
            device = overlayConfig(device, { playback: { audioEqualizerSettings: { enabled: true } } });
            onChange();
        },
        change(volume: number) { device = overlayConfig(device, playback(volume)); onChange(); },
    };
}

beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    vi.spyOn(configApi, 'mine').mockResolvedValue(config('a', null));
    vi.spyOn(configApi, 'site').mockResolvedValue(config('site', 0.6).snapshot);
    vi.spyOn(configApi, 'restoreSite').mockResolvedValue(config('site', 0.4, 2).snapshot);
    vi.spyOn(configApi, 'save').mockImplementation(async (_, __, value) => ({
        schemaVersion: 1, revision: revision(1), config: value,
    }));
});

afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
});

describe('配置同步', () => {
    it('只提交允许的字段，发布范围比个人配置窄', () => {
        const portable = {
            playback: { volume: 0, loopMode: 'one', enableMediaCache: true, audioEqualizerSettings: { enabled: true },
                mediaCacheBytes: 1024, mediaCacheCount: 2 },
            visual: { lyricRenderQuality: 'high', urlBackgroundList: ['https://assets.example.test/cover'] },
            appearance: { mode: 'custom', palette: { apiKey: 'fixture' } },
            interaction: { customShortcutLetter: 'a' },
            developer: { enabled: true },
        };
        expect(selectConfig(portable)).toEqual({
            playback: { volume: 0, loopMode: 'one', enableMediaCache: true, audioEqualizerSettings: { enabled: true } },
            visual: { lyricRenderQuality: 'high' },
            appearance: { mode: 'custom' },
            interaction: { customShortcutLetter: 'a' },
        });
        expect(selectConfig(portable, true)).toEqual({
            playback: { volume: 0, loopMode: 'one' },
            visual: { lyricRenderQuality: 'high' },
            appearance: { mode: 'custom' },
        });
    });

    it('游客使用公开默认，本地调节跨刷新保留且没有上传', async () => {
        let page = fixture();
        await page.service.loadSite(true);
        await advance();
        expect(page.volume()).toBe(0.6);
        page.change(0.45);
        await advance(1500);
        expect(configApi.save).not.toHaveBeenCalled();
        page.close();
        page = fixture(0.9);
        await page.service.loadSite(true);
        await advance();
        expect(page.volume()).toBe(0.45);
        page.close();
    });

    it('首次登录默认开启同步，使用默认配置并在修改后保存个人偏好', async () => {
        const page = fixture();
        await page.service.loadSite(true);
        await advance();
        page.change(0.45);
        await advance(1200);

        await page.service.checkAccount('account');
        await advance(1200);
        expect(page.service.status()).toMatchObject({ auth: 'account', enabled: true, status: 'saved' });
        expect(page.volume()).toBe(0.6);
        expect(configApi.save).not.toHaveBeenCalled();

        page.change(0.55);
        await advance(1200);
        expect(configApi.save).toHaveBeenCalledExactlyOnceWith('mine', revision(0),
            { playback: { volume: 0.55 } }, expect.any(AbortSignal));
        expect(page.service.status().status).toBe('saved');
        page.close();
    });

    it('同步歌曲缓存开关，刷新和关闭开关都保留选择', async () => {
        const account = config('a', 0.8);
        vi.mocked(configApi.mine).mockResolvedValue(account);
        const first = fixture();
        await first.service.checkAccount('account');
        await advance();
        first.changeMediaCache(true);
        await advance(1200);
        expect(configApi.save).toHaveBeenCalledWith('mine', revision(0),
            { playback: { volume: 0.8, loopMode: 'all', enableMediaCache: true } }, expect.any(AbortSignal));
        first.close();

        const saved = await vi.mocked(configApi.save).mock.results[0].value;
        vi.mocked(configApi.mine).mockResolvedValue({ ...account, snapshot: saved });
        const refreshed = fixture();
        await refreshed.service.checkAccount('account');
        await advance();
        expect(refreshed.mediaCache()).toBe(true);
        refreshed.changeMediaCache(false);
        await advance(1200);
        expect(configApi.save).toHaveBeenLastCalledWith('mine', revision(1),
            { playback: { volume: 0.8, loopMode: 'all', enableMediaCache: false } }, expect.any(AbortSignal));
        refreshed.close();
    });

    it('继续使用浏览器已保存的关闭选择', async () => {
        localStorage.setItem('ncm-config:folia:v1:a', JSON.stringify({ enabled: false }));
        const page = fixture();
        await page.service.checkAccount('account');
        await advance();
        expect(page.service.status()).toMatchObject({ auth: 'account', enabled: false, status: 'local' });
        page.change(0.55);
        await advance(1200);
        expect(configApi.save).not.toHaveBeenCalled();
        page.close();
    });

    it('滑条连续变更只上传一次，云端回填不会回传', async () => {
        const page = fixture();
        vi.mocked(configApi.mine).mockResolvedValue(config('a', 0.5));
        await page.service.checkAccount('account');
        await advance(1200);
        expect(page.volume()).toBe(0.5);
        expect(configApi.save).not.toHaveBeenCalled();
        for (const volume of [0.51, 0.55, 0.57, 0.58]) {
            page.change(volume);
            await advance(25);
        }
        await advance(1250);
        expect(configApi.save).toHaveBeenCalledTimes(1);
        expect(configApi.save).toHaveBeenCalledWith('mine', revision(0), playback(0.58), expect.any(AbortSignal));
        expect(page.service.status().status).toBe('saved');
        page.close();
    });

    it('离线草稿属于原账号，换号后应用各自配置，切回后恢复待同步改动', async () => {
        let page = fixture();
        const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
        vi.mocked(configApi.mine).mockResolvedValue(config('a', 0.5));
        await page.service.checkAccount('account');
        await advance();
        page.change(0.49);
        await advance(1200);
        expect(page.service.status().status).toBe('offline');
        expect(configApi.save).not.toHaveBeenCalled();
        page.close();
        page = fixture(0.49);
        vi.mocked(configApi.mine).mockResolvedValue(config('b', 0.25));
        await page.service.checkAccount('account');
        await advance();
        expect(page.volume()).toBe(0.25);
        await page.service.checkAccount('guest');
        await advance();
        expect(page.volume()).toBe(0.8);
        online.mockReturnValue(true);
        vi.mocked(configApi.mine).mockResolvedValue(config('a', 0.5));
        await page.service.checkAccount('account');
        await advance(1200);
        expect(page.volume()).toBe(0.49);
        expect(configApi.save).toHaveBeenCalledWith('mine', revision(0), playback(0.49), expect.any(AbortSignal));
        page.close();
    });

    it('版本冲突保留本机值，确认后才使用新版本保存', async () => {
        const page = fixture();
        vi.mocked(configApi.mine).mockResolvedValue(config('a', 0.5));
        await page.service.checkAccount('account');
        await advance();
        vi.mocked(configApi.save).mockRejectedValueOnce(new ConfigRequestError(409));
        page.change(0.4);
        await advance(1200);
        expect(page.volume()).toBe(0.4);
        expect(page.service.status().status).toBe('conflict');
        vi.mocked(configApi.mine).mockResolvedValue(config('a', 0.3, 1));
        await advance(2000);
        expect(configApi.save).toHaveBeenCalledTimes(1);
        await page.service.resolveConflict(true);
        await advance(1200);
        expect(configApi.save).toHaveBeenLastCalledWith('mine', revision(1), playback(0.4), expect.any(AbortSignal));
        page.close();
    });

    it('同账号两个离线标签页的独立字段重开后一起提交', async () => {
        vi.mocked(configApi.mine).mockResolvedValue(config('a', 0.5));
        const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
        const left = fixture();
        const right = fixture();
        await Promise.all([left.service.checkAccount('account'), right.service.checkAccount('account')]);
        await advance();
        left.change(0.32);
        await advance(200);
        right.changeLoop('one');
        await advance(1300);
        left.close();
        right.close();

        expect(configApi.save).not.toHaveBeenCalled();
        online.mockReturnValue(true);
        const resumed = fixture();
        await resumed.service.checkAccount('account');
        await advance(1300);
        expect(resumed.volume()).toBe(0.32);
        expect(resumed.loop()).toBe('one');
        expect(configApi.save).toHaveBeenCalledExactlyOnceWith('mine', revision(0),
            { playback: { volume: 0.32, loopMode: 'one' } }, expect.any(AbortSignal));
        resumed.close();
    });

    it('请求进行中产生的新改动在确认后继续同步', async () => {
        vi.mocked(configApi.mine).mockResolvedValue(config('a', 0.5));
        let finish!: (value: Awaited<ReturnType<typeof configApi.save>>) => void;
        vi.mocked(configApi.save).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
        const page = fixture();
        await page.service.checkAccount('account');
        await advance();
        page.change(0.55);
        await advance(1100);
        page.changeLoop('one');
        finish(config('a', 0.55, 1).snapshot);
        await advance(1200);
        expect(configApi.save).toHaveBeenLastCalledWith('mine', revision(1),
            { playback: { volume: 0.55, loopMode: 'one' } }, expect.any(AbortSignal));
        page.close();
    });

    it('仅超管的可发布设置改动显示保存入口，改回原值后收起', async () => {
        const page = fixture();
        vi.mocked(configApi.mine).mockResolvedValue({ ...config('admin', 0.5), canPublishSite: true });
        await page.service.loadSite(true);
        await page.service.checkAccount('account');
        await advance();
        expect(page.service.status().siteDirty).toBe(false);
        page.changeEqualizer();
        await advance();
        expect(page.service.status().siteDirty).toBe(false);
        page.changeLoop('one');
        await advance();
        expect(page.service.status().siteDirty).toBe(true);
        expect(configApi.save).not.toHaveBeenCalled();
        page.changeLoop('all');
        await advance();
        expect(page.service.status().siteDirty).toBe(false);
        page.changeLoop('one');
        await advance();
        expect(await page.service.publish()).toBe(true);
        expect(configApi.save).toHaveBeenCalledWith('site', revision(0),
            { playback: { volume: 0.5, loopMode: 'one' } }, expect.any(AbortSignal));
        expect(page.service.status().siteDirty).toBe(false);
        vi.mocked(configApi.mine).mockResolvedValue(config('reader', null));
        await page.service.checkAccount('account');
        await advance();
        page.changeLoop('off');
        await advance();
        expect(page.service.status().siteDirty).toBe(false);
        expect(await page.service.publish()).toBe(false);
        page.close();
    });

    it('站点保存期间继续编辑时，确认提交后仍保留后续改动', async () => {
        const page = fixture();
        vi.mocked(configApi.mine).mockResolvedValue({ ...config('admin', null), canPublishSite: true });
        await page.service.loadSite(true);
        await page.service.checkAccount('account');
        await advance();
        page.change(0.45);
        let finish!: (value: Awaited<ReturnType<typeof configApi.save>>) => void;
        vi.mocked(configApi.save).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
        const pending = page.service.publish();
        page.changeLoop('one');
        finish(config('site', 0.45, 1).snapshot);
        expect(await pending).toBe(true);
        expect(page.service.status().siteDirty).toBe(true);
        expect(await page.service.publish()).toBe(true);
        expect(configApi.save).toHaveBeenLastCalledWith('site', revision(1),
            { playback: { volume: 0.45, loopMode: 'one' } }, expect.any(AbortSignal));
        expect(page.service.status().siteDirty).toBe(false);
        page.close();
    });

    it('清除个人配置后应用站点默认，并用快照版本恢复站点上一版', async () => {
        vi.mocked(configApi.site).mockResolvedValue({ ...config('site', 0.6, 1).snapshot, hasPrevious: true });
        vi.mocked(configApi.mine).mockResolvedValue({ ...config('a', 0.25), canPublishSite: true });
        const page = fixture();
        await page.service.loadSite(true);
        await page.service.checkAccount('account');
        await advance();
        expect(page.volume()).toBe(0.25);
        await page.service.clearPersonal();
        await advance();
        expect(page.service.status().status).toBe('cleared');
        expect(page.volume()).toBe(0.6);
        expect(configApi.save).toHaveBeenCalledExactlyOnceWith('mine', revision(0), {}, expect.any(AbortSignal));
        await page.service.restoreSite();
        expect(configApi.restoreSite).toHaveBeenCalledExactlyOnceWith(revision(1), expect.any(AbortSignal));
        expect(page.service.status().siteStatus).toBe('restored');
        page.close();
    });
});
