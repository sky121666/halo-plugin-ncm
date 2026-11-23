import { useEffect, useRef, useState } from 'react';
import { setStatusMessage } from '../stores/useStatusMessageStore';
import { libraryApi, LibraryRequestError } from './haloLibraryApi';
import { playlistIssue, type PlaylistIssue } from './PlaylistFeedback';

type Operation = { kind: 'write'; next: boolean } | { kind: 'review' };
export type ReactionState = {
    value?: boolean;
    pending?: Operation;
    issue?: PlaylistIssue;
    needsReview?: boolean;
};

const adapters = {
    like: {
        subject: '红心',
        confirmed: (value: boolean) => value ? '已加入喜欢的歌' : '已取消红心',
        write: async (id: string, value: boolean) => (await libraryApi.like(id, value)).liked,
        read: async (id: string, signal: AbortSignal) => (await libraryApi.checkLikes([id], signal)).liked[id],
    },
    favorite: {
        subject: '收藏',
        confirmed: (value: boolean) => value ? '已收藏歌单' : '已取消收藏',
        write: async (id: string, value: boolean) => (await libraryApi.subscribe(id, value)).subscribed,
        read: async (id: string, signal: AbortSignal) => (await libraryApi.subscription(id, signal)).subscribed,
    },
} as const;

export function useLibraryReactions(kind: keyof typeof adapters, onConfirmed?: (id: string, value: boolean) => void) {
    const current = useRef<Record<string, ReactionState>>({});
    const [entries, setEntries] = useState(current.current);
    const locks = useRef(new Map<string, AbortController>());
    const adapter = adapters[kind];

    useEffect(() => {
        const active = locks.current;
        return () => {
            active.forEach(controller => controller.abort());
            active.clear();
        };
    }, []);

    const setEntry = (id: string, entry: ReactionState) => {
        current.current = { ...current.current, [id]: entry };
        setEntries(current.current);
    };

    const run = async (id: string, operation: Operation) => {
        const previous = current.current[id];
        if (locks.current.has(id) || (operation.kind === 'write' && previous?.needsReview)) return;
        const controller = new AbortController();
        locks.current.set(id, controller);
        setEntry(id, { ...previous, pending: operation, issue: undefined });
        let value: boolean;
        try {
            value = operation.kind === 'write'
                ? await adapter.write(id, operation.next)
                : await adapter.read(id, controller.signal);
            if (controller.signal.aborted) return;
            if (typeof value !== 'boolean') throw new LibraryRequestError(502, '未返回' + adapter.subject + '状态。');
        } catch (error) {
            if (controller.signal.aborted) return;
            const issue = playlistIssue(error, operation.kind === 'write', adapter.subject);
            setEntry(id, { value: previous?.value, issue, needsReview: previous?.needsReview || issue.action === 'review' });
            return;
        } finally {
            if (locks.current.get(id) === controller) locks.current.delete(id);
        }
        setEntry(id, { value });
        if (operation.kind === 'write') setStatusMessage({ type: 'success', text: adapter.confirmed(value) });
        onConfirmed?.(id, value);
    };

    return {
        entries,
        toggle: (id: string, value: boolean) => void run(id, { kind: 'write', next: !(current.current[id]?.value ?? value) }),
        review: (id: string) => void run(id, { kind: 'review' }),
    };
}
