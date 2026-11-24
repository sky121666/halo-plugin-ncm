import { useEffect, useSyncExternalStore } from 'react';

const base = '/apis/api.ncm-ai.halo.run/v1alpha1/';
const listeners = new Set<() => void>();
let available = false;
let generationSession = new AbortController();

export const canUseHaloMusicAi = () => available;
const subscribe = (listener: () => void) => {
    listeners.add(listener);
    return () => listeners.delete(listener);
};

function setAvailable(next: boolean) {
    if (available === next) return;
    available = next;
    if (!next) {
        generationSession.abort();
        generationSession = new AbortController();
    }
    listeners.forEach(listener => listener());
}

export const useHaloMusicAi = () => useSyncExternalStore(subscribe, canUseHaloMusicAi, () => false);

export function useHaloMusicAiAvailability(signedIn: boolean) {
    useEffect(() => {
        if (!signedIn) return;
        let disposed = false;
        let request: AbortController | undefined;
        const check = async () => {
            if (document.visibilityState !== 'visible' || request) return;
            const controller = new AbortController();
            request = controller;
            try {
                const response = await fetch(base + 'capabilities/-', {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    redirect: 'manual',
                    headers: { Accept: 'application/json' },
                    signal: AbortSignal.any([controller.signal, AbortSignal.timeout(6000)]),
                });
                const enabled = response.ok && (await response.json()).available === true;
                if (!disposed) setAvailable(enabled);
            } catch {
                if (!disposed) setAvailable(false);
            } finally {
                request = undefined;
            }
        };
        void check();
        window.addEventListener('focus', check);
        window.addEventListener('online', check);
        document.addEventListener('visibilitychange', check);
        return () => {
            disposed = true;
            request?.abort();
            setAvailable(false);
            window.removeEventListener('focus', check);
            window.removeEventListener('online', check);
            document.removeEventListener('visibilitychange', check);
        };
    }, [signedIn]);
}

export class HaloMusicAiError extends Error {
    constructor(message: string, readonly code?: 'SEGMENTATION_TRUNCATED') {
        super(message);
    }
}

const messages: Partial<Record<number, string>> = {
    400: '歌名或歌词格式超出音乐 AI 支持的范围。',
    401: '登录 Halo 后才能使用音乐 AI。',
    403: '当前账号无法使用音乐 AI。',
    404: '音乐 AI 暂不可用。',
    429: 'AI 请求较多，请稍后再试。',
    503: '音乐 AI 暂不可用。',
    504: 'AI 响应超时，请稍后再试。',
};

export async function requestHaloMusicAi(path: 'themes' | 'segmentations', body: unknown, signal?: AbortSignal) {
    if (!available) throw new HaloMusicAiError('音乐 AI 暂不可用。');
    const session = generationSession;
    const response = await fetch(base + path, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'manual',
        signal: signal ? AbortSignal.any([signal, session.signal]) : session.signal,
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-NCM-Request': '1' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        const status = response.type === 'opaqueredirect' ? 401 : response.status;
        if (path === 'segmentations' && status === 502) {
            const problem = await response.json().catch(() => null);
            if (problem?.code === 'SEGMENTATION_TRUNCATED') {
                throw new HaloMusicAiError('AI 分词输出不完整，已保留原分词。', 'SEGMENTATION_TRUNCATED');
            }
        }
        if (session === generationSession && [401, 403, 404, 503].includes(status)) setAvailable(false);
        throw new HaloMusicAiError(messages[status] ?? 'AI 生成失败，请稍后再试。');
    }
    return response.json();
}
