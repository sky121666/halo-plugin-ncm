import type {
    MiniPlayback, PlaybackPosition, PlaybackSession, PlaybackTrack, PlaybackView,
} from './playbackSession.js';
import type { Bounds, Layout, Point, Positions } from './placement.js';

const version = new URL(import.meta.url).search;
function versionedAsset(name: string) {
    const url = new URL(name, import.meta.url);
    url.search = version;
    return url.href;
}

const [{observeLens}, session, {acceptsSwup, SwupFolia}, placement, {PositionPreferences}, appearance] = await Promise.all([
    import(versionedAsset('./lens.js')) as Promise<typeof import('./lens.js')>,
    import(versionedAsset('./playbackSession.js')) as Promise<typeof import('./playbackSession.js')>,
    import(versionedAsset('./swupFolia.js')) as Promise<typeof import('./swupFolia.js')>,
    import(versionedAsset('./placement.js')) as Promise<typeof import('./placement.js')>,
    import(versionedAsset('./positionPreferences.js')) as Promise<typeof import('./positionPreferences.js')>,
    import(versionedAsset('./appearance.js')) as Promise<typeof import('./appearance.js')>,
]);
const {canResumePlayback, isNewerPlaybackSession, isPlaybackId, playbackOwnership,
    musicLyricsPath, readPlaybackSession, readResumablePlaybackSession, rememberPlayback, resumeTimeForMedia} = session;

type Phase = PlaybackView['phase'];
type Session = { version: 1; playlist: string; id: string; position: number; duration: number };
type PlayAddress = { url?: string; status?: string; trial?: {start: number; end: number}; message?: string };
type Song = { id?: string; name?: string; artistNames?: string; coverUrl?: string; durationMs?: number };

const entry = document.querySelector<HTMLScriptElement>('script[data-ncm-mini-player]');
const playlist = entry?.dataset.ncmMiniPlayer;
const sessionKey = 'ncm-mini-player:v1';
const placeholder: PlaybackTrack = {id: '', title: '网抑云', artist: ''};
const tag = 'ncm-mini-player';
const css = versionedAsset('./player.css');
const systemAppearance = window.matchMedia('(prefers-color-scheme: dark)');
let hasSeenDarkClass = false;

function readLegacySession(playlistId: string): Session | null {
    try {
        const saved = JSON.parse(sessionStorage.getItem(sessionKey) ?? 'null') as Session | null;
        if (saved?.version === 1 && saved.playlist === playlistId && Number.isFinite(saved.duration)
            && Number.isFinite(saved.position) && saved.position >= 0 && saved.duration >= 0) return saved;
    } catch {
        // Storage is optional. A blocked storage API leaves the current page playable.
    }
    return null;
}

const clock = (seconds: number) => {
    const s = Math.max(0, Math.floor(seconds));
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
};

class Playback implements MiniPlayback {
    readonly views = new Set<() => void>();
    private tracks: PlaybackTrack[] = [];
    private queue?: Promise<void>;
    queueLoading = false;
    index = 0;
    position = 0;
    duration = 0;
    phase: Phase = 'idle';
    note = '';
    trialSeconds = 0;
    private audio?: HTMLAudioElement;
    private sourceId: string | null = null;
    private request?: AbortController;
    private departing = false;
    private ownsPlayback = false;
    private lastSavedAt = 0;
    private previewOnly = false;
    private readonly ownership = playbackOwnership(() => {
        this.ownsPlayback = false;
        this.request?.abort();
        this.audio?.pause();
        if (this.phase === 'loading') {
            this.phase = 'paused';
            this.note = '';
            this.update();
        }
    });

    constructor(private readonly playlistId: string, private readonly resumeOn: boolean) {
        window.addEventListener('pagehide', () => {
            this.save();
            this.departing = true;
            this.request?.abort();
            this.audio?.pause();
        });
        window.addEventListener('pageshow', event => {
            this.departing = false;
            if (!event.persisted || !this.size) return;
            const saved = readPlaybackSession();
            if (saved && 'closed' in saved && isNewerPlaybackSession(saved, this.lastSavedAt)) {
                this.reset([], 0, 0, 0);
            } else if (saved && !('closed' in saved) && saved.at >= this.lastSavedAt) {
                this.restore(saved);
            } else if (this.phase === 'playing') {
                this.phase = 'paused';
                this.update();
            }
        });
        document.addEventListener('visibilitychange', () => {
            if (document.hidden && !this.departing) this.save();
        });
    }

    get track() { return this.tracks[this.index] ?? placeholder; }
    get size() { return this.tracks.length; }
    get started() { return this.audio !== undefined; }

    useAsPreview() { this.previewOnly = true; }
    view(): PlaybackView {
        const {track, size, index, position, duration, phase, note, queueLoading} = this;
        return {track, size, index, position, duration, phase, trial: this.trialSeconds > 0, note, queueLoading};
    }
    selection(): PlaybackPosition {
        return {queue: this.tracks, index: this.index, time: this.position,
            duration: this.duration, playing: this.phase === 'playing' && !this.audio?.ended};
    }

    handoff() {
        if (this.track.id) this.lastSavedAt = rememberPlayback(this.selection());
    }

    loadQueue() {
        if (this.size || this.queue) return;
        const saved = readPlaybackSession();
        if (saved && !('closed' in saved)) {
            this.restore(saved);
            return;
        }
        this.queueLoading = true;
        this.note = '正在加载歌单…';
        this.update();
        this.queue = (async () => {
            const response = await fetch('/music/playlist/' + this.playlistId + '?format=json&limit=100', {
                signal: AbortSignal.timeout(12000),
            });
            if (!response.ok) throw new Error('playlist-unavailable');
            const result: {playlist?: {tracks?: Song[]}} = await response.json();
            if (!Array.isArray(result.playlist?.tracks)) throw new Error('playlist-unavailable');
            const tracks = result.playlist.tracks.flatMap(song =>
                isPlaybackId(song?.id)
                    ? [{id: song.id, title: song.name || '未知歌曲', artist: song.artistNames || '',
                        cover: song.coverUrl?.startsWith('https://') ? song.coverUrl : undefined, durationMs: song.durationMs}]
                    : []);
            if (!tracks.length) throw new Error('playlist-empty');
            const legacy = saved ? null : readLegacySession(this.playlistId);
            const index = legacy ? tracks.findIndex(track => track.id === legacy.id) : -1;
            this.reset(tracks, Math.max(0, index), index >= 0 ? legacy?.position ?? 0 : 0,
                index >= 0 ? legacy?.duration ?? 0 : 0);
        })().catch(error => {
            this.phase = 'error';
            this.note = error instanceof Error && error.message === 'playlist-empty'
                ? '歌单暂无曲目' : '歌单暂不可用，点击重试';
        }).finally(() => {
            this.queue = undefined;
            this.queueLoading = false;
            this.update();
        });
    }

    restore(saved: PlaybackSession) {
        this.lastSavedAt = saved.at;
        this.reset(saved.queue, saved.index, saved.time, saved.duration);
        if (!this.previewOnly && this.resumeOn && canResumePlayback(saved)) void this.play();
    }

    private reset(tracks: PlaybackTrack[], index: number, position: number, duration: number) {
        this.tracks = tracks;
        this.index = index;
        this.position = position;
        this.phase = this.position > 0 ? 'paused' : 'idle';
        this.duration = duration;
        this.trialSeconds = 0;
        this.note = '';
        if (this.audio?.hasAttribute('src')) {
            this.sourceId = null;
            this.audio.removeAttribute('src');
            this.audio.load();
        }
        this.update();
    }

    update() { this.views.forEach(view => view()); }

    private save(playing = this.phase === 'playing' && !this.audio?.ended) {
        if (this.previewOnly || !this.ownsPlayback || this.departing || !this.track.id) return;
        this.lastSavedAt = rememberPlayback({...this.selection(), playing});
    }

    private getAudio() {
        if (this.audio) return this.audio;
        const audio = new Audio();
        audio.preload = 'none';
        this.audio = audio;
        audio.addEventListener('loadedmetadata', () => this.updatePosition());
        audio.addEventListener('timeupdate', () => this.updatePosition());
        audio.addEventListener('playing', () => {
            if (this.departing || !this.sourceId) return;
            this.ownsPlayback = true;
            this.ownership.claim();
            this.phase = 'playing';
            this.note = this.trialSeconds > 0 ? '试听 · ' + clock(this.trialSeconds) : '';
            this.save();
            this.update();
        });
        audio.addEventListener('pause', () => {
            if (this.departing || this.phase === 'loading' || !this.sourceId) return;
            if (this.phase !== 'error') this.phase = 'paused';
            this.save();
            this.update();
        });
        audio.addEventListener('ended', () => {
            if (this.trialSeconds > 0) {
                this.phase = 'paused';
                this.note = '试听结束';
                this.save();
                this.update();
                return;
            }
            if (this.phase === 'playing' || this.phase === 'paused') this.step(1, true);
        });
        audio.addEventListener('error', () => {
            if (!this.sourceId || this.phase === 'loading') return;
            this.sourceId = null;
            this.phase = 'error';
            this.note = '音频加载失败，点击重试';
            this.update();
        });
        return audio;
    }

    private updatePosition() {
        const audio = this.audio;
        if (!audio || !this.sourceId || this.departing) return;
        this.duration = Number.isFinite(audio.duration) ? audio.duration : 0;
        if (this.trialSeconds > 0) {
            this.duration = this.duration ? Math.min(this.duration, this.trialSeconds) : this.trialSeconds;
        }
        this.position = Math.min(audio.currentTime, this.duration || audio.currentTime);
        if (this.trialSeconds > 0 && this.position >= this.trialSeconds && !audio.paused) {
            audio.pause();
            this.note = '试听结束';
        }
        if (Date.now() - this.lastSavedAt >= 2000 && this.phase === 'playing') this.save();
        this.update();
    }

    toggle() {
        if (!this.size) {
            this.loadQueue();
        } else if (this.phase === 'playing') {
            this.audio?.pause();
        } else if (this.phase === 'loading') {
            this.request?.abort();
            this.request = undefined;
            this.audio?.pause();
            this.phase = this.position > 0 ? 'paused' : 'idle';
            this.note = '';
            this.update();
        } else {
            void this.play();
        }
    }

    step(direction: number, start = this.phase === 'playing' || this.phase === 'loading') {
        if (this.size < 2) return;
        this.request?.abort();
        this.request = undefined;
        this.sourceId = null;
        this.audio?.pause();
        this.index = (this.index + direction + this.size) % this.size;
        this.position = 0;
        this.duration = 0;
        this.trialSeconds = 0;
        this.phase = 'idle';
        this.note = '';
        this.ownsPlayback = true;
        this.save(start);
        this.update();
        if (start) void this.play();
    }

    seek(position: number) {
        if (this.duration === 0) return;
        this.position = Math.max(0, Math.min(position, this.duration));
        if (this.sourceId && this.audio) {
            this.audio.currentTime = this.position;
            this.updatePosition();
        } else {
            this.update();
        }
        this.ownsPlayback = true;
        this.save();
    }

    private async play() {
        if (this.previewOnly) return;
        const track = this.track;
        const audio = this.getAudio();
        const request = new AbortController();
        this.request?.abort();
        this.request = request;
        this.phase = 'loading';
        this.note = '正在取址…';
        this.update();
        try {
            if (this.sourceId !== track.id) {
                const result = await fetch('/music/play/' + track.id + '?level=standard&format=json', {
                    signal: AbortSignal.any([request.signal, AbortSignal.timeout(12000)]),
                    credentials: 'same-origin',
                });
                const address: PlayAddress = await result.json();
                if (request.signal.aborted || this.departing) return;
                if (!result.ok || address.status !== 'ok' || !address.url?.startsWith('https://')) {
                    throw new Error(address.message || '曲目暂时无法播放');
                }
                this.trialSeconds = address.trial
                    ? Math.max(0, (address.trial.end - address.trial.start) / 1000) : 0;
                this.sourceId = track.id;
                audio.src = address.url;
                audio.currentTime = resumeTimeForMedia(this.position, this.trialSeconds, this.trialSeconds > 0);
            }
            if (audio.ended || (this.trialSeconds > 0 && audio.currentTime >= this.trialSeconds)) audio.currentTime = 0;
            await audio.play();
        } catch (error) {
            if (request.signal.aborted || this.departing) return;
            const requiresGesture = error instanceof DOMException && error.name === 'NotAllowedError';
            this.phase = requiresGesture ? 'paused' : 'error';
            this.note = requiresGesture ? '点击继续播放'
                : error instanceof Error && !(error instanceof DOMException) ? error.message : '播放失败，点击重试';
            if (!requiresGesture) this.sourceId = null;
            this.update();
        }
    }
}

if (isPlaybackId(playlist) && !customElements.get(tag)) {
    const {bindPositionDrag, buttonPixels, buttonPosition, layoutFor, moveWithKey, placePanel, readPositions} = placement;
    const sitePosition = (value?: string) => {
        if (!value) return;
        const [x, y] = value.split(',').map(Number);
        return {x, y};
    };
    const defaults: Positions = readPositions({
        wide: sitePosition(entry?.dataset.ncmPositionWide),
        compact: sitePosition(entry?.dataset.ncmPositionCompact),
    });
    const preferences = new PositionPreferences(defaults, () =>
        document.querySelector<MiniPlayer>(tag + '[data-floating]')?.scheduleGeometry());
    const resumeOn = entry?.dataset.ncmResume === 'true';
    const local = new Playback(playlist, resumeOn);
    let playback: MiniPlayback = local;
    let musicNavigation: InstanceType<typeof SwupFolia> | undefined;
    let mountScheduled = false;
    let checkedArrival = false;
    const resumeOnArrival = () => {
        if (checkedArrival || !resumeOn) return;
        checkedArrival = true;
        if (local.started || local.queueLoading) return;
        const saved = readResumablePlaybackSession();
        if (!saved) return;
        local.restore(saved);
        musicNavigation?.resume(readResumablePlaybackSession);
    };
    document.addEventListener('ncm:swup-ready', event => {
        const swup = (event as CustomEvent<unknown>).detail;
        if (musicNavigation || local.started || !acceptsSwup(swup)) return;
        local.useAsPreview();
        musicNavigation = new SwupFolia(swup, local, () => local.update());
        playback = musicNavigation;
        resumeOnArrival();
    });
    document.dispatchEvent(new Event('ncm:request-swup'));
    if (!musicNavigation && resumeOn) {
        // Give the theme's navigation a chance to register before starting a local audio deck.
        if (document.readyState === 'complete') resumeOnArrival();
        else window.addEventListener('load', resumeOnArrival, {once: true});
    }
    const template = document.createElement('template');
    template.innerHTML = `
        <button class="disc" type="button" aria-label="展开音乐播放器" aria-expanded="false" aria-controls="panel">
            <span class="grooves" aria-hidden="true"><span class="sleeve"></span></span>
            <span class="light" aria-hidden="true"></span>
        </button>
        <section id="panel" class="panel" aria-label="音乐播放器" hidden>
            <div class="track">
                <a class="track-link" data-no-swup>
                    <span class="cover"><img alt="" hidden></span>
                    <span class="details">
                        <span class="title-row"><span class="title"></span><span class="member" hidden>会员</span></span>
                        <span class="artist"></span>
                    </span>
                </a>
                <button class="close" type="button" aria-label="收起播放器"><span aria-hidden="true"></span></button>
            </div>
            <div class="timeline">
                <time class="position">0:00</time>
                <input type="range" aria-label="播放进度" min="0" max="1" value="0" step="0.1" disabled>
                <time class="duration">—</time>
            </div>
            <div class="actions">
                <button class="position-options" type="button" aria-label="播放器位置" aria-expanded="false"
                    aria-controls="position-menu" title="播放器位置" hidden><span aria-hidden="true"></span></button>
                <div class="transport">
                    <button class="previous" type="button" aria-label="上一首"><span class="skip-icon" aria-hidden="true"></span></button>
                    <button class="play" type="button" aria-label="播放"><span class="play-icon" aria-hidden="true"></span></button>
                    <button class="next" type="button" aria-label="下一首"><span class="skip-icon" aria-hidden="true"></span></button>
                </div>
                <span class="index"></span>
            </div>
            <div id="position-menu" class="position-menu" role="group" aria-label="调整播放器位置" hidden>
                <button class="move-position" type="button">用方向键移动</button>
                <button class="reset-position" type="button">恢复默认位置</button>
            </div>
            <span class="status" role="status"></span>
        </section>`;

    class MiniPlayer extends HTMLElement {
        private readonly root = this.attachShadow({mode: 'open'});
        private readonly button: HTMLButtonElement;
        private readonly panel: HTMLElement;
        private readonly trackLink: HTMLAnchorElement;
        private readonly trackTitle: HTMLElement;
        private readonly artist: HTMLElement;
        private readonly member: HTMLElement;
        private readonly cover: HTMLImageElement;
        private readonly sleeve: HTMLElement;
        private readonly playButton: HTMLButtonElement;
        private readonly skipButtons: NodeListOf<HTMLButtonElement>;
        private readonly slider: HTMLInputElement;
        private readonly status: HTMLElement;
        private readonly position: HTMLElement;
        private readonly duration: HTMLElement;
        private readonly index: HTMLElement;
        private readonly options: HTMLButtonElement;
        private readonly positionMenu: HTMLElement;
        private listeners?: AbortController;
        private releaseLens?: () => void;
        private panelObserver?: ResizeObserver;
        private geometryFrame = 0;
        private positionDraft?: {layout: Layout; point: Point};
        private keyboardMoving = false;
        private cancelDrag?: () => void;
        private sleeveSource?: string;

        constructor() {
            super();
            const style = document.createElement('link');
            style.rel = 'stylesheet';
            style.href = css;
            this.root.append(style, template.content.cloneNode(true));
            this.button = this.root.querySelector('.disc')!;
            this.panel = this.root.querySelector('.panel')!;
            this.trackLink = this.root.querySelector('.track-link')!;
            this.trackTitle = this.root.querySelector('.title')!;
            this.artist = this.root.querySelector('.artist')!;
            this.member = this.root.querySelector('.member')!;
            this.cover = this.root.querySelector('img')!;
            this.sleeve = this.root.querySelector('.sleeve')!;
            this.playButton = this.root.querySelector('.play')!;
            this.skipButtons = this.root.querySelectorAll('.previous,.next');
            this.slider = this.root.querySelector('input')!;
            this.status = this.root.querySelector('.status')!;
            this.position = this.root.querySelector('.position')!;
            this.duration = this.root.querySelector('.duration')!;
            this.index = this.root.querySelector('.index')!;
            this.options = this.root.querySelector('.position-options')!;
            this.positionMenu = this.root.querySelector('.position-menu')!;
            if (typeof this.panel.showPopover === 'function') this.panel.popover = 'manual';
            style.addEventListener('load', () => {
                this.setAttribute('data-ready', '');
                this.updateGeometry();
            }, {once: true});
            this.button.addEventListener('click', () => {
                if (this.keyboardMoving) this.finishMove(true);
                else this.setOpen(this.panel.hidden === true);
            });
            this.root.querySelector('.close')!.addEventListener('click', () => this.setOpen(false, true));
            this.options.addEventListener('click', () => this.setPositionMenu(this.positionMenu.hidden === true));
            this.root.querySelector('.move-position')!.addEventListener('click', () => {
                this.setOpen(false, true);
                this.keyboardMoving = true;
                this.setAttribute('data-moving', '');
                this.updateDiscLabel();
            });
            this.root.querySelector('.reset-position')!.addEventListener('click', () => {
                preferences.set(this.layout(), null);
                this.setOpen(false, true);
            });
            this.root.querySelector('.previous')!.addEventListener('click', () => playback.step(-1));
            this.root.querySelector('.next')!.addEventListener('click', () => playback.step(1));
            this.playButton.addEventListener('click', () => playback.toggle());
            this.slider.addEventListener('input', () => playback.seek(this.slider.valueAsNumber));
            this.trackLink.addEventListener('click', event => {
                if (!this.trackLink.hasAttribute('href') || event.defaultPrevented) return;
                if (musicNavigation) musicNavigation.openLyrics(event, musicLyricsPath);
                else local.handoff();
            });
            this.button.addEventListener('keydown', this.moveWithKeyboard);
            this.root.addEventListener('keydown', event => {
                if ((event as KeyboardEvent).key !== 'Escape' || event.defaultPrevented) return;
                this.cancelDrag?.();
                if (!this.positionMenu.hidden) this.setPositionMenu(false, true);
                else if (!this.panel.hidden) this.setOpen(false, true);
            });
        }

        connectedCallback() {
            scheduleMount();
            this.listeners = new AbortController();
            const {signal} = this.listeners;
            this.options.hidden = !this.hasAttribute('data-floating');
            if (this.hasAttribute('data-floating')) {
                this.cancelDrag = bindPositionDrag(this.button, {
                    origin: () => this.keyboardMoving ? undefined : this.buttonOrigin(),
                    move: this.moveTo,
                    finish: point => {
                        if (point) this.moveTo(point);
                        this.finishMove(!!point);
                    },
                }, signal);
                void preferences.refresh();
                window.addEventListener('online', () => void preferences.refresh(true), {signal});
                window.addEventListener('focus', () => void preferences.refresh(), {signal});
                window.addEventListener('pageshow', event => {
                    if (event.persisted) void preferences.refresh(true);
                }, {signal});
            }
            window.addEventListener('resize', this.scheduleGeometry, {signal});
            window.visualViewport?.addEventListener('resize', this.scheduleGeometry, {signal});
            window.visualViewport?.addEventListener('scroll', this.scheduleGeometry, {signal});
            document.addEventListener('scroll', () => {
                if (!this.panel.hidden) this.scheduleGeometry();
            }, {capture: true, passive: true, signal});
            let appearanceFrame: number | undefined;
            const scheduleAppearance = () => {
                if (appearanceFrame !== undefined) return;
                appearanceFrame = requestAnimationFrame(() => {
                    appearanceFrame = undefined;
                    this.updateAppearance();
                });
            };
            const appearanceObserver = new MutationObserver(scheduleAppearance);
            appearanceObserver.observe(this, {attributeFilter: ['class', 'style']});
            for (let parent = this.parentElement; parent; parent = parent.parentElement) {
                appearanceObserver.observe(parent, {attributes: true});
            }
            signal.addEventListener('abort', () => {
                appearanceObserver.disconnect();
                if (appearanceFrame !== undefined) cancelAnimationFrame(appearanceFrame);
            }, {once: true});
            systemAppearance.addEventListener('change', scheduleAppearance, {signal});
            window.addEventListener('load', scheduleAppearance, {once: true, signal});
            window.addEventListener('pageshow', scheduleAppearance, {signal});
            window.addEventListener('resize', scheduleAppearance, {passive: true, signal});
            this.updateAppearance();
            document.addEventListener('pointerdown', event => {
                if (!this.panel.hidden && !event.composedPath().includes(this)) this.setOpen(false);
            }, {signal});
            let musicWasOpen = false;
            document.addEventListener('swup:page:view', () => {
                const musicIsOpen = location.pathname.replace(/\/$/, '') === '/music';
                if (musicWasOpen && !musicIsOpen && this.hasAttribute('data-floating')) void preferences.refresh(true);
                musicWasOpen = musicIsOpen;
                scheduleAppearance();
                this.cancelDrag?.();
                if (this.keyboardMoving) this.finishMove(false);
                this.setOpen(false);
                this.scheduleGeometry();
            }, {signal});
            local.views.add(this.render);
            this.render();
            this.scheduleGeometry();
        }

        disconnectedCallback() {
            local.views.delete(this.render);
            this.listeners?.abort();
            if (this.keyboardMoving) this.finishMove(false);
            this.setOpen(false);
            cancelAnimationFrame(this.geometryFrame);
            this.geometryFrame = 0;
            scheduleMount();
        }

        private updateAppearance = () => {
            const parent = this.parentElement;
            if (!parent) return;
            const style = getComputedStyle(this);
            // Class-only themes use the absence of .dark to express light mode.
            hasSeenDarkClass ||= document.documentElement.classList.contains('dark')
                || document.body?.classList.contains('dark') === true;
            const scheme = appearance.colorSchemeFromCss(style.getPropertyValue('--ncm-player-color-scheme'))
                ?? appearance.themeColorScheme(style)
                ?? appearance.colorSchemeFromCss(getComputedStyle(parent).colorScheme);
            const dark = scheme === 'dark' || (scheme === 'auto' || (!scheme && !hasSeenDarkClass)) && systemAppearance.matches;
            this.toggleAttribute('data-ncm-dark', dark);
        };

        private layout() { return layoutFor(document.documentElement.clientWidth); }

        private buttonOrigin(): Point {
            const rect = this.getBoundingClientRect();
            return {x: rect.left, y: rect.top};
        }

        private visibleBounds(): Bounds {
            const viewport = window.visualViewport;
            const root = document.documentElement;
            const style = getComputedStyle(this);
            const inset = (side: string) => 12 + (parseFloat(style.getPropertyValue('--_ncm-safe-' + side)) || 0);
            const left = viewport?.offsetLeft ?? 0;
            const top = viewport?.offsetTop ?? 0;
            return {
                left: left + inset('left'), top: top + inset('top'),
                right: left + (viewport?.width ?? root.clientWidth) - inset('right'),
                bottom: top + (viewport?.height ?? root.clientHeight) - inset('bottom'),
            };
        }

        scheduleGeometry = () => {
            if (this.geometryFrame) return;
            this.geometryFrame = requestAnimationFrame(() => {
                this.geometryFrame = 0;
                this.updateGeometry();
            });
        };

        private updateGeometry() {
            if (!this.isConnected || !this.hasAttribute('data-ready') || this.hasAttribute('data-duplicate')) return;
            const bounds = this.visibleBounds();
            if (this.hasAttribute('data-floating')) {
                const layout = this.layout();
                const chosen = this.positionDraft?.layout === layout ? this.positionDraft.point : preferences.position(layout);
                if (!chosen) this.removeAttribute('data-positioned');
                const origin = this.buttonOrigin();
                const size = {width: this.offsetWidth, height: this.offsetHeight};
                const position = chosen ?? buttonPosition(origin, bounds, size);
                const point = buttonPixels(position, bounds, size);
                if (chosen || Math.abs(point.x - origin.x) > .5 || Math.abs(point.y - origin.y) > .5) {
                    this.style.setProperty('--_ncm-x', point.x + 'px');
                    this.style.setProperty('--_ncm-y', point.y + 'px');
                    this.setAttribute('data-positioned', '');
                }
            }
            if (this.panel.hidden) return;

            const anchor = this.getBoundingClientRect();
            const style = this.panel.style;
            const width = Math.min(288, Math.max(1, bounds.right - bounds.left));
            style.width = width + 'px';
            const height = Math.max(this.panel.offsetHeight, this.panel.scrollHeight + 2);
            const next = placePanel(anchor, {width, height}, bounds);
            const detached = !!this.panel.popover;
            style.left = (next.x - (detached ? 0 : anchor.left)) + 'px';
            style.top = (next.y - (detached ? 0 : anchor.top)) + 'px';
            style.maxHeight = next.maximumHeight + 'px';
            style.transformOrigin = next.originX + 'px ' + next.originY + 'px';
            this.panel.dataset.side = next.side;
        }

        private moveTo = (point: Point) => {
            if (!this.positionDraft) this.setOpen(false);
            this.positionDraft = {
                layout: this.layout(),
                point: buttonPosition(point, this.visibleBounds(), {width: this.offsetWidth, height: this.offsetHeight}),
            };
            this.setAttribute('data-moving', '');
            this.scheduleGeometry();
        };

        private finishMove(save: boolean) {
            const draft = this.positionDraft;
            this.positionDraft = undefined;
            this.keyboardMoving = false;
            this.removeAttribute('data-moving');
            if (save && draft) preferences.set(draft.layout, draft.point);
            this.updateDiscLabel();
            this.scheduleGeometry();
        }

        private moveWithKeyboard = (event: KeyboardEvent) => {
            if (!this.keyboardMoving) return;
            if (event.key === 'Tab') {
                this.finishMove(false);
                return;
            }
            const draft = this.positionDraft?.point;
            const origin = draft ? buttonPixels(draft, this.visibleBounds(),
                {width: this.offsetWidth, height: this.offsetHeight}) : this.buttonOrigin();
            const point = moveWithKey(event, origin);
            if (point) this.moveTo(point);
            else if (event.key === 'Enter' || event.key === ' ') this.finishMove(true);
            else if (event.key === 'Escape') this.finishMove(false);
            else return;
            event.preventDefault();
        };

        private updateDiscLabel() {
            const label = this.keyboardMoving ? '调整小播放器位置，方向键移动，回车保存，Escape 取消'
                : this.panel.hidden ? '展开音乐播放器' : '收起音乐播放器';
            this.button.setAttribute('aria-label', label);
        }

        private setPositionMenu(open: boolean, focusButton = false) {
            this.positionMenu.hidden = !open;
            this.options.setAttribute('aria-expanded', String(open));
            if (open) this.positionMenu.querySelector('button')?.focus();
            else if (focusButton) this.options.focus();
        }

        private setOpen(open: boolean, focusButton = false) {
            if (open && this.panel.hidden) {
                this.panel.hidden = false;
                if (this.panel.popover) this.panel.showPopover();
                this.updateGeometry();
                this.panelObserver = new ResizeObserver(this.scheduleGeometry);
                this.panelObserver.observe(this.panel);
                this.panelObserver.observe(this);
                this.releaseLens = observeLens(this.panel);
                playback.loadQueue();
            } else if (!open && !this.panel.hidden) {
                if (this.panel.popover && this.panel.matches(':popover-open')) this.panel.hidePopover();
                this.panel.hidden = true;
                this.setPositionMenu(false);
                this.panelObserver?.disconnect();
                this.panelObserver = undefined;
                this.releaseLens?.();
                this.releaseLens = undefined;
            }
            this.button.setAttribute('aria-expanded', String(open));
            this.updateDiscLabel();
            if (focusButton) this.button.focus();
            this.render();
        }

        private render = () => {
            const {track, position, duration, phase, index, size, queueLoading, note, trial} = playback.view();
            this.dataset.phase = phase;
            this.button.title = phase === 'playing' ? track.title + ' · 正在播放' : '音乐播放器';
            if (this.sleeveSource !== track.cover) {
                this.sleeveSource = track.cover;
                this.sleeve.style.backgroundImage = track.cover ? 'url(' + JSON.stringify(track.cover) + ')' : '';
            }
            if (this.panel.hidden) return;
            this.trackTitle.textContent = track.title;
            this.artist.textContent = track.artist;
            this.member.hidden = !trial;
            if (track.id) {
                this.trackLink.setAttribute('href', musicLyricsPath);
                this.trackLink.title = '查看《' + track.title + '》的歌词';
            } else {
                this.trackLink.removeAttribute('href');
                this.trackLink.removeAttribute('title');
            }
            this.cover.hidden = !track.cover;
            if (track.cover && this.cover.src !== track.cover) this.cover.src = track.cover;
            if (!track.cover) this.cover.removeAttribute('src');
            this.position.textContent = clock(position);
            this.duration.textContent = duration > 0 ? clock(duration) : '—';
            this.slider.max = String(duration || 1);
            this.slider.value = String(duration > 0 ? position : 0);
            this.slider.disabled = duration === 0 || phase === 'loading';
            this.slider.setAttribute('aria-valuetext', clock(position) + ' / ' + (duration > 0 ? clock(duration) : '未知'));
            this.slider.style.setProperty('--played', (duration ? position / duration * 100 : 0) + '%');
            this.playButton.setAttribute('aria-label', phase === 'playing' ? '暂停' : phase === 'loading'
                ? '取消加载' : phase === 'error' ? '重试播放' : position > 0 ? '继续播放' : '播放');
            this.playButton.title = phase === 'error' ? note : '';
            this.playButton.disabled = queueLoading;
            for (const button of this.skipButtons) button.disabled = size < 2;
            this.index.textContent = size ? String(index + 1) + ' / ' + String(size) : '';
            if (this.status.textContent !== note) this.status.textContent = note;
        };
    }

    customElements.define(tag, MiniPlayer);
    function mount() {
        if (!document.body) return;
        const automatic = document.querySelector(tag + '[data-floating]');
        const slots = Array.from(document.querySelectorAll<HTMLElement>(tag + ':not([data-floating])'));
        if (slots.length) {
            slots.forEach((slot, index) => slot.toggleAttribute('data-duplicate', index > 0));
            automatic?.remove();
        } else if (!automatic) {
            const element = document.createElement(tag);
            element.setAttribute('data-floating', '');
            document.body.append(element);
        }
    }
    function scheduleMount() {
        if (mountScheduled) return;
        mountScheduled = true;
        queueMicrotask(() => {
            mountScheduled = false;
            mount();
        });
    }
    scheduleMount();
    document.addEventListener('swup:page:view', scheduleMount);
}
