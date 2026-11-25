import type { FoliaFrame, FoliaPlayback, PlaybackPosition, PlaybackView } from './playbackSession.js';

interface MusicVisit {
    to: { url: string; document?: Document };
    animation: { wait: boolean };
    containers: string[];
    meta: Record<string, unknown>;
}

interface MusicNavigation {
    navigate(url: string): void;
    hooks: {
        before(name: 'visit:transition', handler: (visit: MusicVisit) => void): void;
        on(name: 'visit:start' | 'page:view', handler: (visit: MusicVisit) => void): void;
        once(name: 'disable', handler: () => void): void;
    };
}

const musicPath = '/music';
const isMusicHome = (url: string) => new URL(url, location.origin).pathname.replace(/\/$/, '') === musicPath;
const pagePath = () => location.pathname + location.search + location.hash;

function acceptsSwup(value: unknown): value is MusicNavigation {
    const candidate = value as MusicNavigation | undefined;
    return typeof candidate?.navigate === 'function' && typeof candidate.hooks?.on === 'function'
        && typeof candidate.hooks.before === 'function' && typeof candidate.hooks.once === 'function'
        && typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal === 'function';
}

export class FoliaPlayer {
    private engine?: FoliaPlayback;
    private opening?: Promise<FoliaPlayback>;
    private unsubscribe?: () => void;
    private shell?: { dialog: HTMLDialogElement; status: HTMLDivElement };
    private swup?: MusicNavigation;
    private returnTo = pagePath();
    private pendingPlay?: AbortController;
    private failure?: string;
    private selection;

    constructor(private readonly playlistId: string,
                private readonly session: Pick<typeof import('./playbackSession.js'),
                    'readPlaybackSession' | 'readResumablePlaybackSession'>,
                private readonly update: () => void) {
        this.selection = this.readSelection();
    }

    private readSelection(): PlaybackPosition | null {
        const saved = this.session.readPlaybackSession();
        return saved && !('closed' in saved) ? saved : null;
    }

    attachSwup(value: unknown) {
        if (this.swup || !acceptsSwup(value)) return;
        this.swup = value;
        value.hooks.on('visit:start', visit => {
            if (isMusicHome(visit.to.url)) visit.animation.wait = true;
        });
        value.hooks.before('visit:transition', visit => {
            const incoming = visit.to.document;
            if (!isMusicHome(visit.to.url) || !incoming?.querySelector('#root[data-ncm-config]')) return;
            // The Folia frame stays outside every container Swup replaces.
            const title = incoming.title;
            incoming.head.replaceWith(document.head.cloneNode(true));
            incoming.title = title;
            visit.containers = [];
            visit.meta.ncmMusic = true;
        });
        value.hooks.on('page:view', visit => {
            if (visit.meta.ncmMusic) this.show(visit.to.url);
            else {
                this.returnTo = pagePath();
                this.shell?.dialog.close();
            }
        });
        value.hooks.once('disable', () => {
            this.swup = undefined;
            this.shell?.dialog.close();
        });
    }

    openLyrics(event: MouseEvent, href: string) {
        if (!this.swup || event.button !== 0 || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return;
        event.preventDefault();
        this.swup.navigate(href);
    }

    get hasTrack() { return Boolean(this.engine?.view().track.id); }

    view(): PlaybackView {
        const live = this.engine?.view();
        const saved = this.selection;
        const visible = live ?? {
            track: saved?.queue[saved.index] ?? {id: '', title: '网抑云', artist: ''},
            size: saved?.queue.length ?? 0, index: saved?.index ?? 0,
            position: saved?.time ?? 0, duration: saved?.duration ?? 0,
            phase: saved ? 'paused' as const : 'idle' as const, trial: false, note: '',
        };
        if (this.pendingPlay) return {...visible, phase: 'loading',
            note: this.engine ? '正在加载歌单…' : '正在打开播放器…'};
        return this.failure ? {...visible, phase: 'error', note: this.failure} : visible;
    }

    resume() {
        if (this.pendingPlay || this.engine || !this.session.readResumablePlaybackSession()) return;
        this.playWhenReady(engine => {
            const saved = this.session.readResumablePlaybackSession();
            if (saved) engine.play(saved);
        });
    }

    toggle() {
        if (this.pendingPlay) {
            this.pendingPlay.abort();
            this.pendingPlay = undefined;
            this.update();
            return;
        }
        if (this.engine?.view().track.id && !this.failure) {
            this.engine.toggle();
            return;
        }
        this.selection = this.readSelection();
        const selection = this.selection;
        this.playWhenReady((engine, signal) => selection
            ? engine.play(selection) : engine.playPlaylist(this.playlistId, signal));
    }

    step(direction: -1 | 1) { this.engine?.step(direction); }
    seek(position: number) { this.engine?.seek(position); }

    private playWhenReady(start: (engine: FoliaPlayback, signal: AbortSignal) => void | Promise<void>) {
        const request = this.pendingPlay = new AbortController();
        this.failure = undefined;
        this.update();
        void this.ready().then(engine => {
            if (!request.signal.aborted) return start(engine, request.signal);
        }).catch((error: unknown) => {
            if (!request.signal.aborted) {
                this.failure = error instanceof Error ? error.message : '播放器加载失败，点击重试';
            }
        }).finally(() => {
            if (this.pendingPlay !== request) return;
            this.pendingPlay = undefined;
            this.update();
        });
    }

    private createShell() {
        const dialog = document.createElement('dialog');
        dialog.dataset.ncmFoliaView = '';
        dialog.setAttribute('aria-label', '音乐页');
        const status = document.createElement('div');
        status.dataset.ncmFoliaStatus = '';
        status.hidden = true;
        status.setAttribute('role', 'status');
        dialog.append(status);
        dialog.addEventListener('cancel', event => {
            if (!this.swup) return;
            event.preventDefault();
            this.swup.navigate(this.returnTo);
        });
        document.body.append(dialog);
        return {dialog, status};
    }

    private show(url: string) {
        const shell = this.shell ??= this.createShell();
        const lyrics = new URL(url, location.origin).searchParams.get('view') === 'lyrics';
        const target = pagePath();
        const showLyrics = (engine: FoliaPlayback) => {
            if (lyrics && shell.dialog.open && pagePath() === target) {
                engine.openLyrics(this.pendingPlay ? null : this.readSelection());
            }
        };
        if (!shell.dialog.open) shell.dialog.showModal();
        shell.status.hidden = !!this.engine;
        if (this.engine) {
            showLyrics(this.engine);
            return;
        }
        shell.status.textContent = '正在加载音乐页…';
        void this.ready().then(engine => {
            shell.status.hidden = true;
            showLyrics(engine);
        }).catch(() => {
            if (pagePath() !== target) return;
            const fallback = document.createElement('a');
            fallback.textContent = lyrics ? '打开歌词界面' : '打开独立音乐页';
            fallback.href = target;
            fallback.setAttribute('data-no-swup', '');
            shell.status.replaceChildren('音乐页加载失败', fallback);
        });
    }

    private ready(): Promise<FoliaPlayback> {
        if (this.engine) return Promise.resolve(this.engine);
        if (this.opening) return this.opening;
        const shell = this.shell ??= this.createShell();
        const frame: FoliaFrame = document.createElement('iframe');
        frame.dataset.ncmFoliaFrame = '';
        frame.title = '网抑云';
        frame.src = musicPath;
        frame.ncmReturnToBlog = () => {
            if (this.swup) this.swup.navigate('/');
            else location.assign('/');
        };
        this.opening = new Promise((resolve, reject) => {
            const timeout = window.setTimeout(() => {
                frame.remove();
                this.opening = undefined;
                reject(new Error('播放器加载失败，点击重试'));
            }, 20_000);
            frame.addEventListener('ncm:playback-ready', () => {
                const engine = frame.ncmPlayback;
                if (!engine) return;
                window.clearTimeout(timeout);
                this.opening = undefined;
                this.engine = engine;
                this.unsubscribe?.();
                this.unsubscribe = engine.subscribe(this.update);
                this.failure = undefined;
                this.update();
                resolve(engine);
            });
            shell.dialog.append(frame);
        });
        return this.opening;
    }
}
