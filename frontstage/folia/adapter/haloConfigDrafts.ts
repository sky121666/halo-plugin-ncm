import { overlayConfig, selectConfig, type ConfigDocument } from './haloConfigDocument';

type Change = {
    revision: string;
    fields: ConfigDocument;
    at: number;
};

type Entry = Change & { key: string };

const namespace = 'ncm-config:folia:v1:pending:';
const prefix = (profile: string) => namespace + profile + ':';

export class HaloConfigDrafts {
    private readonly tab = Array.from(crypto.getRandomValues(new Uint32Array(2)), n => n.toString(36)).join('-');
    private readonly inMemory = new Map<string, Change>();
    private next = 0;

    add(profile: string, revision: string, fields: ConfigDocument, at = Date.now()) {
        const key = prefix(profile) + this.tab + ':' + String(this.next++).padStart(9, '0');
        const edit: Change = { revision, fields, at };
        try {
            // The key is unique to this edit. A response in another tab can only remove the exact
            // records it submitted, even if this tab keeps editing during that request.
            localStorage.setItem(key, JSON.stringify(edit));
        } catch {
            this.inMemory.set(key, edit);
        }
    }

    list(profile: string) {
        const first = prefix(profile);
        const edits: Entry[] = [];
        try {
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (!key?.startsWith(first)) continue;
                try {
                    const edit = JSON.parse(localStorage.getItem(key) ?? 'null') as Change | null;
                    if (typeof edit?.revision === 'string' && Number.isFinite(edit.at)) {
                        edits.push({ key, revision: edit.revision, fields: selectConfig(edit.fields), at: edit.at });
                    }
                } catch {
                    // An unreadable record does not prevent recovering the other edits.
                }
            }
        } catch {
            // The dialog can still retry in-memory edits from this page.
        }
        for (const [key, edit] of this.inMemory) {
            if (key.startsWith(first)) edits.push({ key, ...edit });
        }
        return edits.sort((a, b) => a.at - b.at || a.key.localeCompare(b.key));
    }

    discard(edits: Entry[]) {
        for (const { key } of edits) {
            this.inMemory.delete(key);
            try { localStorage.removeItem(key); } catch { /* Storage may have been disabled. */ }
        }
    }

    rebaseOwn(profile: string, from: string, to: string) {
        if (from === to) return;
        const own = prefix(profile) + this.tab + ':';
        const changes = this.list(profile).filter(edit => edit.key.startsWith(own) && edit.revision === from);
        for (const edit of changes) this.add(profile, to, edit.fields, edit.at);
        this.discard(changes);
    }
}

export function applyConfigDrafts(cloud: ConfigDocument | null | undefined, edits: Entry[]) {
    return overlayConfig(cloud, ...edits.map(edit => edit.fields));
}
