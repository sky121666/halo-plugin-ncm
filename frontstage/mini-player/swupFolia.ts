import type { FoliaFrame, FoliaPlayback, MiniPlayback, PlaybackPosition, PlaybackView } from './playbackSession.js';

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
    };
}

const musicPath = '/music';
const isMusicHome = (url: string) => new URL(url, location.origin).pathname.replace(/\/$/, '') === musicPath;
const pagePath = () => location.pathname + location.search + location.hash;

export function acceptsSwup(value: unknown): value is MusicNavigation {
    const candidate = value as MusicNavigation | undefined;
    return typeof candidate?.navigate === 'function' && typeof candidate.hooks?.on === 'function'
        && typeof candidate.hooks.before === 'function' && typeof HTMLDialogElement !== 'undefined'
        && typeof HTMLDialogElement.prototype.showModal === 'function';
}

export class SwupFolia implements MiniPlayback {
    private engine?: FoliaPlayback;
    private opening?: Promise<FoliaPlayback>;
    private unsubscribe?: () => void;
    private shell?: { dialog: HTMLDialogElement; status: HTMLDivElement };
    private returnTo = pagePath();
    private pendingPlay?: symbol;
    private failed = false;

    constructor(private readonly swup: MusicNavigation,
                private readonly preview: MiniPlayback & { selection(): PlaybackPosition; handoff(): void },
                private readonly update: () => void) {
        swup.hooks.on('visit:start', visit => {
            if (isMusicHome(visit.to.url)) visit.animation.wait = true;
        });
        swup.hooks.before('visit:transition', visit => {
            const incoming = visit.to.document;
            if (!isMusicHome(visit.to.url) || !incoming?.querySelector('#root[data-ncm-config]')) return;
            // The dialog is outside the theme's replacement containers. Keep the theme's head
            // assets in place while Swup updates the URL, title and browser history.
            const title = incoming.title;
            incoming.head.replaceWith(document.head.cloneNode(true));
            incoming.title = title;
            visit.containers = [];
            visit.meta.ncmMusic = true;
        });
        swup.hooks.on('page:view', visit => {
            if (visit.meta.ncmMusic) this.show(visit.to.url);
            else {
                this.returnTo = pagePath();
                this.shell?.dialog.close();
            }
        });
    }

    openLyrics(event: MouseEvent, href: string) {
        if (!this.engine?.view().track.id && !this.pendingPlay) this.preview.handoff();
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return;
        event.preventDefault();
        this.swup.navigate(href);
    }

    view(): PlaybackView {
        const live = this.engine?.view();
        const visible = live?.track.id ? live : this.preview.view();
        if (this.pendingPlay) return {...visible, phase: 'loading', note: '正在打开播放器…'};
        return this.failed ? {...visible, phase: 'error', note: '播放器加载失败，点击重试'} : visible;
    }

    loadQueue() {
        if (!this.engine?.view().track.id) this.preview.loadQueue();
    }

    resume(readPosition: () => PlaybackPosition | null) {
        if (this.pendingPlay || this.engine) return;
        this.playWhenReady(readPosition);
    }

    toggle() {
        if (this.pendingPlay) {
            this.pendingPlay = undefined;
            this.update();
            return;
        }
        if (this.engine?.view().track.id) {
            this.engine.toggle();
            return;
        }
        if (!this.preview.view().size) {
            this.loadQueue();
            return;
        }
        this.playWhenReady(() => this.preview.selection());
    }

    private playWhenReady(readPosition: () => PlaybackPosition | null) {
        const request = this.pendingPlay = Symbol();
        this.failed = false;
        this.update();
        void this.ready().then(engine => {
            if (this.pendingPlay !== request) return;
            this.pendingPlay = undefined;
            const position = readPosition();
            if (position) engine.play(position);
            this.update();
        }).catch(() => {
            if (this.pendingPlay !== request) return;
            this.pendingPlay = undefined;
            this.failed = true;
            this.update();
        });
    }

    step(direction: -1 | 1) {
        if (this.engine?.view().track.id) this.engine.step(direction);
        else this.preview.step(direction);
    }

    seek(position: number) {
        if (this.engine?.view().track.id) this.engine.seek(position);
        else this.preview.seek(position);
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
            if (lyrics && shell.dialog.open && pagePath() === target) engine.openLyrics(this.preview.selection());
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
        frame.ncmReturnToBlog = () => this.swup.navigate('/');
        this.opening = new Promise((resolve, reject) => {
            const timeout = window.setTimeout(() => {
                frame.remove();
                this.opening = undefined;
                reject(new Error('Folia did not initialize'));
            }, 20_000);
            frame.addEventListener('ncm:playback-ready', () => {
                const engine = frame.ncmPlayback;
                if (!engine) return;
                window.clearTimeout(timeout);
                this.opening = undefined;
                this.engine = engine;
                this.unsubscribe?.();
                this.unsubscribe = engine.subscribe(this.update);
                this.failed = false;
                this.update();
                resolve(engine);
            });
            shell.dialog.append(frame);
        });
        return this.opening;
    }
}
