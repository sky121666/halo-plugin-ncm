// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    observeMusicColorScheme, readMusicColorPreference, resolveMusicColorScheme, saveMusicColorPreference,
} from '../../../src/halo/appearance';
import { changedConfig, overlayConfig, selectConfig } from '../../../src/halo/haloConfigDocument';

const siteKey = 'ncm:site-color-scheme:v1';
const musicKey = 'ncm:music-color-scheme:v1';

beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal('matchMedia', () => Object.assign(new EventTarget(), { matches: true }));
});

afterEach(() => {
    document.documentElement.removeAttribute('data-ncm-site-color-scheme');
    document.querySelector('iframe[data-ncm-folia-frame]')?.remove();
    vi.unstubAllGlobals();
});

describe('音乐页明暗模式', () => {
    it('首次访问跟随站点，保留旧版手动偏好和系统模式', () => {
        expect(readMusicColorPreference()).toBe('site');
        localStorage.setItem('default_theme_daylight', 'true');
        expect(readMusicColorPreference()).toBe('light');
        localStorage.setItem('follow_system_theme', 'true');
        expect(readMusicColorPreference()).toBe('system');
        saveMusicColorPreference('site');
        expect(readMusicColorPreference()).toBe('site');
    });

    it('独立页读取站点记录，系统模式和手动选择独立生效', () => {
        expect(resolveMusicColorScheme('site')).toBe('dark');
        localStorage.setItem(siteKey, 'light');
        expect(resolveMusicColorScheme('site')).toBe('light');
        expect(resolveMusicColorScheme('system')).toBe('dark');
        expect(resolveMusicColorScheme('dark')).toBe('dark');
        localStorage.setItem(siteKey, 'auto');
        expect(resolveMusicColorScheme('site')).toBe('dark');
    });

    it('内嵌页优先读取博客实时状态，跟随切换不写入所选模式', async () => {
        const frame = document.createElement('iframe');
        frame.dataset.ncmFoliaFrame = '';
        document.body.append(frame);
        vi.stubGlobal('frameElement', frame);
        localStorage.setItem(siteKey, 'dark');
        document.documentElement.dataset.ncmSiteColorScheme = 'light';
        const update = vi.fn(() => resolveMusicColorScheme('site'));
        const stop = observeMusicColorScheme(update);
        try {
            expect(update).toHaveLastReturnedWith('light');
            document.documentElement.dataset.ncmSiteColorScheme = 'dark';
            await Promise.resolve();
            expect(update).toHaveLastReturnedWith('dark');
            expect(localStorage.getItem(musicKey)).toBeNull();
        } finally {
            stop();
        }
    });

    it('旧个人设置覆盖新站点默认，现有配色来源保持可用', () => {
        const site = { appearance: { colorScheme: 'site', mode: 'default' } };
        expect(overlayConfig(site, { appearance: { daylight: false, mode: 'custom' } }).appearance)
            .toEqual({ colorScheme: 'dark', mode: 'custom' });
        expect(overlayConfig(site, { visual: { followSystemTheme: true } }).appearance.colorScheme).toBe('system');
        expect(selectConfig({ appearance: { colorScheme: 'invalid' } })).toEqual({});
    });

    it('整页回读时旧版站点默认不会覆盖新的个人跟随模式', () => {
        const siteDefault = { appearance: { daylight: false }, visual: { followSystemTheme: false } };
        const savedChoice = { appearance: { colorScheme: 'site' } };
        const desired = overlayConfig(siteDefault, savedChoice);
        expect(desired).toEqual(savedChoice);
        expect(changedConfig(savedChoice, desired)).toEqual({});
    });
});
