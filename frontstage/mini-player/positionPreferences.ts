import type { Layout, Point, PositionEdits, Positions } from './placement.js';

const placementUrl = new URL('./placement.js', import.meta.url);
placementUrl.search = new URL(import.meta.url).search;
const {applyEdits, layouts, readEdits, readPositions} = await import(placementUrl.href) as typeof import('./placement.js');
const api = '/apis/api.ncm.halo.run/v1alpha1/my-configs/mini-player';
const storageKey = 'ncm-mini-player:position:v1:';
const token = /^[a-f0-9]{48}$/;

interface Snapshot { schemaVersion: number; revision: string; config?: unknown }
interface Viewer { profileKey: string; snapshot: Snapshot }
interface Profile { key: string; snapshot: Snapshot; edits: PositionEdits }

function stored(key: string): unknown {
    try { return JSON.parse(localStorage.getItem(storageKey + key) ?? 'null'); } catch { return null; }
}

function store(key: string, value: Positions | PositionEdits) {
    try { localStorage.setItem(storageKey + key, JSON.stringify(value)); } catch { /* Storage is optional. */ }
}

class RequestError extends Error {
    constructor(readonly status: number) { super('Mini player position: ' + status); }
}

async function request(options?: RequestInit) {
    const result = await fetch(api, {
        ...options, cache: 'no-store', credentials: 'same-origin', redirect: 'manual',
        signal: AbortSignal.timeout(9000),
    });
    if (!result.ok) throw new RequestError(result.type === 'opaqueredirect' ? 401 : result.status);
    return result.json();
}

function snapshot(value: Snapshot): Snapshot {
    if (value?.schemaVersion !== 1 || !token.test(value.revision)) throw new RequestError(422);
    return {...value, config: readPositions(value.config)};
}

async function readViewer(): Promise<Viewer | undefined> {
    try {
        const viewer = await request() as Viewer;
        if (!token.test(viewer?.profileKey)) throw new RequestError(422);
        return {profileKey: viewer.profileKey, snapshot: snapshot(viewer.snapshot)};
    } catch (error) {
        if (error instanceof RequestError && (error.status === 401 || error.status === 403)) return;
        throw error;
    }
}

export class PositionPreferences {
    private guest = readPositions(stored('guest'));
    private profile?: Profile;
    private settled = false;
    private waiting: PositionEdits = {};
    private checking = false;
    private saving = false;
    private refreshAfterSave = false;
    private lastChecked = 0;

    constructor(private readonly defaults: Positions, private readonly changed: () => void) {}

    position(layout: Layout): Point | undefined {
        const current = this.profile
            ? applyEdits(readPositions(this.profile.snapshot.config), this.profile.edits)
            : applyEdits(this.guest, this.waiting);
        return current[layout] ?? this.defaults[layout];
    }

    set(layout: Layout, position: Point | null) {
        if (this.profile) {
            this.profile.edits = {...this.profile.edits, [layout]: position};
            store(this.profile.key, this.profile.edits);
            void this.save();
        } else if (this.settled) {
            this.guest = applyEdits(this.guest, {[layout]: position});
            store('guest', this.guest);
        } else {
            this.waiting = {...this.waiting, [layout]: position};
        }
        this.changed();
    }

    async refresh(force = false) {
        if (this.saving) {
            this.refreshAfterSave ||= force;
            return;
        }
        if (this.checking || !force && Date.now() - this.lastChecked < 2000) return;
        this.lastChecked = Date.now();
        this.checking = true;
        try {
            this.accept(await readViewer());
        } catch {
            // Local edits remain available while the connection recovers.
            return;
        } finally {
            this.checking = false;
        }
        void this.save();
    }

    private accept(viewer?: Viewer) {
        if (viewer) {
            const key = viewer.profileKey;
            const existing = this.profile?.key === key ? this.profile.edits : readEdits(stored(key));
            const edits = {...existing, ...this.waiting};
            this.profile = {key, snapshot: viewer.snapshot, edits};
            if (Object.keys(edits).length) store(key, edits);
        } else {
            this.profile = undefined;
            this.guest = applyEdits(readPositions(stored('guest')), this.waiting);
            if (Object.keys(this.waiting).length) store('guest', this.guest);
        }
        this.waiting = {};
        this.settled = true;
        this.changed();
    }

    private async save() {
        if (this.saving || this.checking || !this.profile) return;
        this.saving = true;
        const profile = this.profile;
        let retriedConflict = false;
        try {
            while (this.profile === profile && Object.keys(profile.edits).length) {
                const sent = profile.edits;
                const config = applyEdits(readPositions(profile.snapshot.config), sent);
                try {
                    profile.snapshot = snapshot(await request({
                        method: 'PUT', headers: {'Content-Type': 'application/json', 'X-NCM-Request': '1'},
                        body: JSON.stringify({schemaVersion: 1, revision: profile.snapshot.revision, config}),
                    }));
                    const edits = {...profile.edits};
                    for (const layout of layouts) {
                        if (edits[layout] === sent[layout]) delete edits[layout];
                    }
                    profile.edits = edits;
                    store(profile.key, edits);
                    retriedConflict = false;
                } catch (error) {
                    if (!(error instanceof RequestError)) return;
                    if (error.status === 401 || error.status === 403) this.accept();
                    if (error.status !== 409 || retriedConflict) return;
                    retriedConflict = true;
                    const viewer = await readViewer();
                    if (viewer?.profileKey !== profile.key) {
                        this.accept(viewer);
                        return;
                    }
                    profile.snapshot = viewer.snapshot;
                }
            }
        } catch {
            // Pending edits are stored for the next read or reconnect.
        } finally {
            this.saving = false;
            this.changed();
            if (this.refreshAfterSave) {
                this.refreshAfterSave = false;
                void this.refresh(true);
            }
        }
    }
}
