import { beforeEach, describe, expect, it, vi } from 'vitest';

const account = { bound: true, userId: 42, boundAt: '2026-01-01T00:00:00Z' };

beforeEach(() => { vi.resetModules(); });

describe('Folia 取址与账号状态', () => {
    it('首次读到已有绑定时保留当前 Halo 会话发出的取址请求', async () => {
        const { foliaSessionSignal, requireFoliaBinding, setFoliaBinding } =
            await import('../../../src/halo/haloFoliaBinding');
        const inFlight = foliaSessionSignal();

        expect(() => requireFoliaBinding()).toThrow();
        expect(setFoliaBinding(account)).toBe(true);
        expect(inFlight.aborted).toBe(false);
        expect(requireFoliaBinding(account.userId)).toBe(inFlight);
    });

    it('已知游客登录、重新绑定和退出时中止旧身份请求', async () => {
        const { foliaSessionSignal, requireFoliaBinding, setFoliaBinding } =
            await import('../../../src/halo/haloFoliaBinding');
        setFoliaBinding();
        const guest = foliaSessionSignal();
        setFoliaBinding(account);
        expect(guest.aborted).toBe(true);

        const previous = requireFoliaBinding(account.userId);
        expect(setFoliaBinding(account)).toBe(false);
        expect(previous.aborted).toBe(false);
        setFoliaBinding({ ...account, userId: 43 });
        expect(previous.aborted).toBe(true);

        const rebound = requireFoliaBinding(43);
        setFoliaBinding();
        expect(rebound.aborted).toBe(true);
        expect(() => requireFoliaBinding()).toThrow();
    });
});
