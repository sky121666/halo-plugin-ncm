import { schemaVersion, selectConfig, type ConfigDocument } from './haloConfigDocument';

export interface ConfigSnapshot {
    schemaVersion: number;
    revision: string;
    config?: ConfigDocument | null;
    hasPrevious?: boolean;
}

export interface MyConfig {
    profileKey: string;
    snapshot: ConfigSnapshot;
    canPublishSite: boolean;
    canViewDiagnostics: boolean;
}

const base = '/apis/api.ncm.halo.run/v1alpha1/';
const mine = 'my-configs/folia';
const site = 'config-defaults/folia';
const token = /^[a-f0-9]{48}$/;

export class ConfigRequestError extends Error {
    constructor(readonly status: number) { super(`Halo config: ${status}`); }
}

async function request(path: string, signal: AbortSignal, body?: {
    schemaVersion: number;
    revision: string;
    config?: ConfigDocument;
}, method = body ? 'PUT' : 'GET') {
    const headers = new Headers({ Accept: 'application/json' });
    if (body) {
        headers.set('Content-Type', 'application/json');
        headers.set('X-NCM-Request', '1');
    }
    const response = await fetch(base + path, {
        method, headers, credentials: 'same-origin', redirect: 'manual', cache: 'no-store',
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.any([signal, AbortSignal.timeout(12000)]),
    });
    if (!response.ok) throw new ConfigRequestError(response.type === 'opaqueredirect' ? 401 : response.status);
    return await response.json();
}

function snapshot(value: ConfigSnapshot): ConfigSnapshot {
    if (value?.schemaVersion !== schemaVersion || !token.test(value.revision)) {
        throw new ConfigRequestError(422);
    }
    return { ...value, config: value.config == null ? null : selectConfig(value.config) };
}

export const configApi = {
    async mine(signal: AbortSignal): Promise<MyConfig> {
        const result = await request(mine, signal) as MyConfig;
        if (!token.test(result?.profileKey)) throw new ConfigRequestError(422);
        return {
            profileKey: result.profileKey,
            snapshot: snapshot(result.snapshot),
            canPublishSite: result.canPublishSite === true,
            canViewDiagnostics: result.canViewDiagnostics === true,
        };
    },
    async site(signal: AbortSignal) {
        return snapshot(await request(site, signal));
    },
    async restoreSite(revision: string, signal: AbortSignal) {
        return snapshot(await request(site + '/restore', signal, { schemaVersion, revision }, 'POST'));
    },
    async save(scope: 'mine' | 'site', revision: string, config: ConfigDocument, signal: AbortSignal) {
        return snapshot(await request(scope === 'mine' ? mine : site, signal, { schemaVersion, revision, config }));
    },
};
