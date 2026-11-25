import type { Bounds, Layout, Point, Positions } from './placement.js';

const version = new URL(import.meta.url).search;
function versionedAsset(name: string) {
    const url = new URL(name, import.meta.url);
    url.search = version;
    return url.href;
}

const [{observeLens}, session, {FoliaPlayer}, placement, {PositionPreferences}, appearance] = await Promise.all([
    import(versionedAsset('./lens.js')) as Promise<typeof import('./lens.js')>,
    import(versionedAsset('./playbackSession.js')) as Promise<typeof import('./playbackSession.js')>,
    import(versionedAsset('./foliaPlayer.js')) as Promise<typeof import('./foliaPlayer.js')>,
    import(versionedAsset('./placement.js')) as Promise<typeof import('./placement.js')>,
    import(versionedAsset('./positionPreferences.js')) as Promise<typeof import('./positionPreferences.js')>,
    import(versionedAsset('./appearance.js')) as Promise<typeof import('./appearance.js')>,
]);
const {isPlaybackId, musicLyricsPath} = session;

const entry = document.querySelector<HTMLScriptElement>('script[data-ncm-mini-player]');
const playlist = entry?.dataset.ncmMiniPlayer;
const tag = 'ncm-mini-player';
const css = versionedAsset('./player.css');
const systemAppearance = window.matchMedia('(prefers-color-scheme: dark)');
const contactDisplayTag = 'halo-contact-form-auto-display';
const contactControlTag = 'halo-contact-form-floating';
const contactTrigger = 'button.trigger';
let hasSeenDarkClass = false;

const clock = (seconds: number) => {
    const s = Math.max(0, Math.floor(seconds));
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
};

if (isPlaybackId(playlist) && !customElements.get(tag)) {
    const {avoidFloatingControls, bindPositionDrag, buttonPixels, buttonPosition, layoutFor, moveWithKey, placePanel, readPositions} = placement;
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
    const views = new Set<() => void>();
    const playback = new FoliaPlayer(playlist, session, () => views.forEach(render => render()));
    let mountScheduled = false;
    document.addEventListener('ncm:swup-ready', event => playback.attachSwup((event as CustomEvent<unknown>).detail));
    document.dispatchEvent(new Event('ncm:request-swup'));
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
        private stylesLoaded = false;
        private initialPlacementReady = false;

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
                this.stylesLoaded = true;
                this.reveal();
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
                playback.openLyrics(event, musicLyricsPath);
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
                const contactsReady = this.observeContactControls(signal);
                window.addEventListener('load', this.scheduleGeometry, {once: true, signal});
                const positionReady = preferences.refresh(false, AbortSignal.timeout(2500));
                void Promise.all([positionReady, contactsReady]).then(() => {
                    if (signal.aborted) return;
                    this.initialPlacementReady = true;
                    this.reveal();
                });
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
            views.add(this.render);
            this.render();
            this.reveal();
            this.scheduleGeometry();
        }

        disconnectedCallback() {
            views.delete(this.render);
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

        private reveal() {
            if (!this.isConnected || !this.stylesLoaded || this.hasAttribute('data-ready')) return;
            if (this.hasAttribute('data-floating') && !this.initialPlacementReady) return;
            this.setAttribute('data-ready', '');
            this.updateGeometry();
        }

        private observeContactControls(signal: AbortSignal) {
            const config = (window as Window & {
                autoContactFrom?: {displayFormConfig?: {displayForm?: string}}[];
            }).autoContactFrom;
            const expected = !this.hasAttribute('data-ready') && Array.isArray(config)
                ? config.filter(item => item?.displayFormConfig?.displayForm === 'aside_button').length : 0;
            return new Promise<void>(resolve => {
                // A disabled or unavailable form may never render its trigger.
                const timeout = expected ? window.setTimeout(resolve, 2000) : undefined;
                const finish = () => {
                    window.clearTimeout(timeout);
                    resolve();
                };
                const observe = () => {
                    observer.disconnect();
                    observer.observe(document.body, {childList: true});
                    for (const display of document.querySelectorAll(contactDisplayTag)) {
                        observer.observe(display, {childList: true});
                    }
                    let rendered = 0;
                    for (const control of document.querySelectorAll(contactControlTag)) {
                        const root = control.shadowRoot;
                        if (!root) continue;
                        observer.observe(root, {childList: true, subtree: true});
                        if (root.querySelector(contactTrigger)) rendered++;
                    }
                    if (rendered >= expected) finish();
                    this.scheduleGeometry();
                };
                const observer = new MutationObserver(observe);
                signal.addEventListener('abort', () => {
                    observer.disconnect();
                    finish();
                }, {once: true});
                observe();
            });
        }

        private contactControlBounds(): Bounds[] {
            return Array.from(document.querySelectorAll(contactControlTag)).flatMap(control => {
                const trigger = control.shadowRoot?.querySelector(contactTrigger);
                if (!trigger || getComputedStyle(trigger).visibility !== 'visible') return [];
                const rect = trigger.getBoundingClientRect();
                return rect.width && rect.height ? [rect] : [];
            });
        }

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
                const preferred = buttonPixels(position, bounds, size);
                const point = this.positionDraft ? preferred
                    : avoidFloatingControls(preferred, bounds, size, this.contactControlBounds());
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
            const {track, position, duration, phase, index, size, note, trial} = playback.view();
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
            this.slider.disabled = !playback.hasTrack || duration === 0 || phase === 'loading';
            this.slider.setAttribute('aria-valuetext', clock(position) + ' / ' + (duration > 0 ? clock(duration) : '未知'));
            this.slider.style.setProperty('--played', (duration ? position / duration * 100 : 0) + '%');
            this.playButton.setAttribute('aria-label', phase === 'playing' ? '暂停' : phase === 'loading'
                ? '取消加载' : phase === 'error' ? '重试播放' : position > 0 ? '继续播放' : '播放');
            this.playButton.title = phase === 'error' ? note : '';
            for (const button of this.skipButtons) button.disabled = !playback.hasTrack || size < 2 || phase === 'loading';
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
    if (entry?.dataset.ncmResume === 'true') playback.resume();
}
