import { configApi, ConfigRequestError, type ConfigSnapshot, type MyConfig } from './haloConfigApi';
import { changedConfig, overlayConfig, selectConfig, type ConfigDocument } from './haloConfigDocument';
import type { ConfigDriver } from './foliaConfigDriver';
import { applyConfigDrafts, HaloConfigDrafts } from './haloConfigDrafts';

type Draft = { enabled: boolean; revision: string; config?: ConfigDocument | null; dirty: boolean };
type Profile = { key: string; draft: Draft };
type SyncStatus = 'loading' | 'local' | 'saved' | 'pending' | 'saving' | 'offline' | 'error' | 'conflict' | 'clearing' | 'cleared';
type AuthState = 'loading' | 'guest' | 'account' | 'error';
export type ConfigState = {
    auth: AuthState;
    viewer?: MyConfig;
    enabled: boolean;
    status: SyncStatus;
    siteStatus: 'loading' | 'ready' | 'saving' | 'saved' | 'restored' | 'conflict' | 'error';
    siteDirty: boolean;
    siteHasPrevious?: boolean;
};

const prefix = 'ncm-config:folia:v1:';
const isEmpty = (doc: ConfigDocument) => Object.keys(doc).length === 0;
const equal = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
const nextPaint = () => new Promise<void>(resolve => {
    // Background tabs can suspend animation frames completely.
    const timer = window.setTimeout(resolve, 70);
    window.requestAnimationFrame(() => {
        window.clearTimeout(timer);
        resolve();
    });
});

function readLocal<T>(key: string): T | undefined {
    try {
        const raw = localStorage.getItem(prefix + key);
        return raw ? JSON.parse(raw) as T : undefined;
    } catch {
        return undefined;
    }
}

function writeLocal(key: string, data: unknown) {
    try {
        localStorage.setItem(prefix + key, JSON.stringify(data));
    } catch {
        // The active draft is still in memory when device storage is unavailable.
    }
}

function legacyPlaybackOverride(config: ConfigDocument) {
    try {
        const old = JSON.parse(localStorage.getItem('ncm-player:prefs') ?? 'null');
        const override = {
            ...(typeof old?.volume === 'number' || localStorage.getItem('player_volume') !== null
                ? { volume: config.playback?.volume } : {}),
            ...(typeof old?.mode === 'string' || localStorage.getItem('player_loop_mode') !== null
                ? { loopMode: config.playback?.loopMode } : {}),
        };
        return selectConfig({ playback: override });
    } catch {
        return {};
    }
}

export class HaloConfigSync {
    private state: ConfigState = { auth: 'loading', enabled: false, status: 'loading', siteStatus: 'loading', siteDirty: false };
    private listeners = new Set<() => void>();
    private readonly drafts = new HaloConfigDrafts();
    private driver?: ConfigDriver;
    private base?: ConfigDocument;
    private guest: ConfigDocument = {};
    private applied: ConfigDocument = {};
    private floor?: ConfigDocument | null;
    private site?: ConfigSnapshot;
    private siteEditBaseline?: ConfigDocument;
    private profile?: Profile;
    private viewerRequest?: AbortController;
    private siteRequest?: AbortController;
    private writeRequest?: AbortController;
    private publishRequest?: AbortController;
    private saveTimer?: number;
    private changeTimer?: number;
    private projecting = false;
    private projection = Promise.resolve();
    private projectionId = 0;

    status = () => this.state;
    subscribe = (listener: () => void) => {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    };

    private set(partial: Partial<ConfigState>) {
        this.state = { ...this.state, ...partial };
        this.listeners.forEach(listener => listener());
    }

    attach(driver: ConfigDriver) {
        this.driver = driver;
        const current = driver.capture();
        const baseline = readLocal<ConfigDocument>('baseline');
        // Older baselines predate this portable preference. Preserve the existing browser choice.
        this.base = baseline
            ? overlayConfig(selectConfig({ playback: { enableMediaCache: current.playback?.enableMediaCache } }),
                selectConfig(baseline))
            : current;
        this.guest = selectConfig(readLocal<ConfigDocument>('guest') ?? (!baseline ? legacyPlaybackOverride(current) : {}));
        writeLocal('baseline', this.base);
        writeLocal('guest', this.guest);
        this.applied = current;
        const unsubscribe = driver.subscribe(this.changed);
        this.project();
        return () => {
            this.flushChanges();
            unsubscribe();
            this.driver = undefined;
        };
    }

    changed = () => {
        if (!this.driver || this.projecting) return;
        window.clearTimeout(this.changeTimer);
        this.changeTimer = window.setTimeout(this.flushChanges, 100);
    };

    flushChanges = () => {
        window.clearTimeout(this.changeTimer);
        if (!this.driver || this.projecting) return;
        const current = this.driver.capture();
        const previous = this.applied;
        const change = changedConfig(previous, current);
        this.applied = current;
        if (this.state.viewer?.canPublishSite) {
            this.updateSiteDirty(this.siteEditBaseline ?? selectConfig(previous, true), selectConfig(current, true));
        }
        if (isEmpty(change)) return;
        const profile = this.profile;
        if (profile?.draft.enabled) {
            this.recordChanges(profile, change);
            if (this.state.status !== 'conflict' && this.state.status !== 'clearing') {
                this.set({ status: 'pending' });
                this.scheduleSave();
            }
            return;
        }
        this.guest = overlayConfig(this.guest, change);
        writeLocal('guest', this.guest);
    };

    private updateSiteDirty(baseline: ConfigDocument, current: ConfigDocument) {
        const siteDirty = !equal(baseline, current);
        this.siteEditBaseline = siteDirty ? baseline : undefined;
        if (siteDirty !== this.state.siteDirty) this.set({ siteDirty });
    }

    private project() {
        const id = ++this.projectionId;
        this.projecting = true;
        this.projection = this.projection.then(async () => {
            const driver = this.driver;
            if (id !== this.projectionId) return;
            try {
                if (!driver || !this.base) return;
                const draft = this.profile?.draft;
                const desired = overlayConfig(this.base, this.floor, draft?.enabled ? draft.config : this.guest);
                const delta = changedConfig(driver.capture(), desired);
                if (!isEmpty(delta)) {
                    await driver.restore(delta);
                    await nextPaint();
                }
                this.applied = driver.capture();
                if (this.siteEditBaseline) {
                    this.updateSiteDirty(this.siteEditBaseline, selectConfig(this.applied, true));
                }
            } catch (error) {
                console.warn('[Halo 配置同步] 无法应用配置', error);
                this.set({ status: 'error' });
            } finally {
                if (id === this.projectionId) this.projecting = false;
            }
        });
    }

    async loadSite(applyDefaults: boolean) {
        this.siteRequest?.abort();
        const request = new AbortController();
        this.siteRequest = request;
        this.set({ siteStatus: 'loading' });
        try {
            const snapshot = await configApi.site(request.signal);
            if (request.signal.aborted) return;
            this.flushChanges();
            this.site = snapshot;
            if (applyDefaults) {
                this.floor = selectConfig(snapshot.config, true);
                this.project();
            }
            this.set({ siteStatus: 'ready', siteHasPrevious: snapshot.hasPrevious === true });
        } catch {
            if (!request.signal.aborted) this.set({ siteStatus: 'error' });
        }
    }

    async checkAccount(auth: AuthState) {
        this.viewerRequest?.abort();
        if (auth === 'loading' || auth === 'guest') {
            this.switchToLocal(auth);
            return;
        }
        if (auth === 'error') this.set({ auth, viewer: undefined, status: 'error' });
        const request = new AbortController();
        this.viewerRequest = request;
        try {
            const viewer = await configApi.mine(request.signal);
            if (!request.signal.aborted) this.acceptViewer(viewer);
        } catch (error) {
            if (request.signal.aborted) return;
            if (error instanceof ConfigRequestError && (error.status === 401 || error.status === 403)) {
                this.switchToLocal('guest');
            } else {
                this.set({ auth: 'error', viewer: undefined, status: 'error' });
            }
        }
    }

    private switchToLocal(auth: AuthState) {
        this.flushChanges();
        const restore = Boolean(this.profile?.draft.enabled);
        this.abortWrites();
        this.profile = undefined;
        this.siteEditBaseline = undefined;
        this.set({ auth, enabled: false, viewer: undefined, status: auth === 'loading' ? 'loading' : 'local', siteDirty: false });
        if (restore) this.project();
    }

    private acceptViewer(viewer: MyConfig) {
        this.flushChanges();
        const same = this.profile?.key === viewer.profileKey;
        if (!same) this.abortWrites();
        const siteDirty = same && viewer.canPublishSite && this.state.siteDirty;
        if (!siteDirty) this.siteEditBaseline = undefined;
        const snapshot = viewer.snapshot;
        const previousDraft = this.profile?.draft;
        if (same && this.writeRequest && previousDraft) {
            this.set({ auth: 'account', viewer, siteDirty });
            return;
        }
        const stored = readLocal<Draft>(viewer.profileKey);
        if (stored?.dirty && stored.config) {
            // Move a draft from the earlier single-key format the first time this account is read.
            this.drafts.add(viewer.profileKey, stored.revision, selectConfig(stored.config), 0);
        }
        const draft: Draft = {
            enabled: (same ? previousDraft?.enabled : stored?.enabled) ?? true,
            revision: snapshot.revision,
            config: snapshot.config,
            dirty: false,
        };
        const profile = { key: viewer.profileKey, draft };
        this.profile = profile;
        this.persist(profile);
        this.set({ auth: 'account', viewer, enabled: draft.enabled, siteDirty });
        const status = this.readDraft(profile, snapshot);
        this.set({ status: draft.enabled ? status : 'local' });
        if (!same || !equal(draft.config, previousDraft?.config) || draft.enabled !== previousDraft?.enabled) {
            this.project();
        }
        if (draft.enabled && status === 'pending') this.scheduleSave();
    }

    private recordChanges(profile: Profile, fields: ConfigDocument) {
        this.drafts.add(profile.key, profile.draft.revision, fields);
        profile.draft.config = overlayConfig(profile.draft.config, fields);
        profile.draft.dirty = true;
    }

    private readDraft(profile: Profile, snapshot: ConfigSnapshot): SyncStatus {
        const edits = this.drafts.list(profile.key);
        const config = applyConfigDrafts(snapshot.config, edits);
        profile.draft.config = edits.length ? config : snapshot.config;
        profile.draft.revision = snapshot.revision;
        profile.draft.dirty = edits.length > 0;
        if (!profile.draft.dirty) return 'saved';
        return !equal(config, snapshot.config ?? {}) && edits.some(edit => edit.revision !== snapshot.revision)
            ? 'conflict' : 'pending';
    }

    setEnabled(enabled: boolean) {
        const profile = this.profile;
        const viewer = this.state.viewer;
        if (!profile || !viewer || !this.driver || this.projecting || this.state.status === 'clearing') return;
        this.flushChanges();
        if (!enabled) {
            window.clearTimeout(this.saveTimer);
            this.writeRequest?.abort();
            this.writeRequest = undefined;
            profile.draft.enabled = false;
            this.set({ enabled: false, status: 'local' });
        } else {
            profile.draft.enabled = true;
            if (!profile.draft.config) {
                this.recordChanges(profile, this.driver.capture());
            }
            const status = this.readDraft(profile, viewer.snapshot);
            this.set({ enabled: true, status });
            if (status === 'pending') this.scheduleSave();
        }
        this.persist(profile);
        this.project();
    }

    private persist(profile: Profile) {
        writeLocal(profile.key, { enabled: profile.draft.enabled });
    }

    private abortWrites() {
        window.clearTimeout(this.saveTimer);
        this.writeRequest?.abort();
        this.writeRequest = undefined;
        this.publishRequest?.abort();
        this.publishRequest = undefined;
        if (this.state.siteStatus === 'saving') this.set({ siteStatus: 'ready' });
    }

    private scheduleSave() {
        window.clearTimeout(this.saveTimer);
        if (!this.profile?.draft.dirty || !this.profile.draft.enabled || !this.state.viewer
            || this.writeRequest || this.state.status === 'conflict') return;
        this.saveTimer = window.setTimeout(() => void this.save(), 950);
    }

    clearPersonal = () => this.save(true);

    private async save(clear = false) {
        const profile = this.profile;
        const viewer = this.state.viewer;
        if (!profile || !viewer || this.writeRequest
            || (!clear && (!profile.draft.enabled || this.state.status === 'conflict'))) return;
        this.flushChanges();
        if (!navigator.onLine) {
            this.set({ status: clear ? 'error' : 'offline' });
            return;
        }
        const edits = this.drafts.list(profile.key);
        const revision = viewer.snapshot.revision;
        const submitted = clear ? {} : applyConfigDrafts(viewer.snapshot.config, edits);
        if (!clear && !edits.length) {
            profile.draft.dirty = false;
            this.set({ status: 'saved' });
            return;
        }
        if (!clear && !equal(submitted, viewer.snapshot.config ?? {}) && edits.some(edit => edit.revision !== revision)) {
            this.readDraft(profile, viewer.snapshot);
            this.project();
            this.set({ status: 'conflict' });
            return;
        }
        window.clearTimeout(this.saveTimer);
        const request = new AbortController();
        this.writeRequest = request;
        this.set({ status: clear ? 'clearing' : 'saving' });
        let continueSaving = false;
        try {
            const saved = await configApi.save('mine', revision, submitted, request.signal);
            if (request.signal.aborted || this.profile !== profile) return;
            this.flushChanges();
            this.drafts.discard(edits);
            this.drafts.rebaseOwn(profile.key, revision, saved.revision);
            const status = this.readDraft(profile, saved);
            const latestViewer = this.state.viewer;
            this.set({ viewer: latestViewer && { ...latestViewer, snapshot: saved },
                status: clear && status === 'saved' ? 'cleared' : status });
            this.project();
            continueSaving = profile.draft.enabled && status === 'pending';
        } catch (error) {
            if (request.signal.aborted || this.profile !== profile) return;
            const status = error instanceof ConfigRequestError ? error.status : 0;
            if (status === 401 || status === 403) {
                this.set({ auth: 'error', viewer: undefined, status: 'error' });
            } else {
                this.set({ status: !clear && status === 409 ? 'conflict' : !navigator.onLine ? 'offline' : 'error' });
            }
        } finally {
            if (this.writeRequest === request) {
                this.writeRequest = undefined;
                if (continueSaving) this.scheduleSave();
            }
        }
    }

    async resolveConflict(keepLocal: boolean) {
        const old = this.profile;
        if (!old?.draft.enabled) return;
        this.flushChanges();
        await this.checkAccount('account');
        const profile = this.profile;
        const viewer = this.state.viewer;
        if (!profile || profile.key !== old.key || !viewer || this.state.status !== 'conflict') return;
        const edits = this.drafts.list(profile.key);
        if (keepLocal) {
            this.drafts.add(profile.key, viewer.snapshot.revision, applyConfigDrafts({}, edits));
        }
        this.drafts.discard(edits);
        const status = this.readDraft(profile, viewer.snapshot);
        this.set({ status });
        if (status === 'pending') this.scheduleSave();
        this.project();
    }

    publish = () => this.writeSite(false);
    restoreSite = () => this.writeSite(true);

    private async writeSite(restore: boolean) {
        if (!this.state.viewer?.canPublishSite || !this.site || !this.driver || this.publishRequest
            || this.state.siteStatus === 'conflict' || this.projecting || (restore && !this.site.hasPrevious)) return false;
        const viewer = this.state.viewer;
        const request = new AbortController();
        this.publishRequest = request;
        this.set({ siteStatus: 'saving' });
        try {
            this.flushChanges();
            const submitted = restore ? undefined : selectConfig(this.applied, true);
            const snapshot = submitted
                ? await configApi.save('site', this.site.revision, submitted, request.signal)
                : await configApi.restoreSite(this.site.revision, request.signal);
            if (request.signal.aborted || this.state.viewer?.profileKey !== viewer.profileKey) return false;
            this.site = snapshot;
            if (submitted) {
                this.flushChanges();
                this.updateSiteDirty(submitted, selectConfig(this.applied, true));
            }
            this.set({ siteStatus: restore ? 'restored' : 'saved', siteHasPrevious: snapshot.hasPrevious === true });
            return true;
        } catch (error) {
            if (request.signal.aborted) return false;
            if (error instanceof ConfigRequestError && (error.status === 401 || error.status === 403)) {
                this.set({ auth: 'error', viewer: undefined, status: 'error' });
            }
            this.set({ siteStatus: error instanceof ConfigRequestError && error.status === 409 ? 'conflict' : 'error' });
            return false;
        } finally {
            if (this.publishRequest === request) this.publishRequest = undefined;
        }
    }

    dispose() {
        this.viewerRequest?.abort();
        this.siteRequest?.abort();
        this.abortWrites();
        window.clearTimeout(this.changeTimer);
    }
}
