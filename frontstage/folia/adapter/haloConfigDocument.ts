import policy from './foliaConfigPolicy.json';

export type ConfigValue = null | boolean | number | string | ConfigValue[] | { [key: string]: ConfigValue };
export type ConfigGroup = Record<string, ConfigValue>;
export type ConfigDocument = Record<string, ConfigGroup>;

export const schemaVersion = policy.schemaVersion;
const fieldName = /^[A-Za-z0-9_.-]{1,100}$/;
const unsafeText = /\b(?:https?|file|blob|data|javascript|vbscript):|(?:url|expression)\s*\(|[<>\u0000-\u001f\u007f]/i;
const reserved = new Set([
    'proto', 'prototype', 'constructor', 'cookie', 'cookies', 'authorization', 'apikey', 'accesstoken',
    'refreshtoken', 'authtoken', 'token', 'secret', 'password', 'permissions', 'privileges',
    'canpublishsite', 'canviewdiagnostics', 'debug', 'developer', 'consolelog',
]);

function record(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function portable(value: unknown, depth = 0): ConfigValue | undefined {
    if (depth > 10) return undefined;
    if (value === null || typeof value === 'boolean') return value;
    if (typeof value === 'string') return value.length <= 2048 && !unsafeText.test(value) ? value : undefined;
    if (typeof value === 'number') return Number.isFinite(value) ? value : undefined;
    if (Array.isArray(value)) {
        if (value.length > 128) return undefined;
        const items = value.map(item => portable(item, depth + 1));
        return items.every(item => item !== undefined) ? items : undefined;
    }
    if (!record(value)) return undefined;
    const entries = Object.entries(value).sort(([a], [b]) => a.localeCompare(b));
    if (entries.length > 160) return undefined;
    const safe: ConfigGroup = {};
    for (const [key, item] of entries) {
        if (!fieldName.test(key) || reserved.has(key.toLowerCase().replace(/[^a-z0-9]/g, ''))) return undefined;
        if (item === undefined) continue;
        const child = portable(item, depth + 1);
        if (child === undefined) return undefined;
        safe[key] = child;
    }
    return safe;
}

function matches(rule: string, value: ConfigValue): boolean {
    if (rule.endsWith('?')) return value === null || matches(rule.slice(0, -1), value);
    if (rule.startsWith('enum:')) return typeof value === 'string' && rule.slice(5).split('|').includes(value);
    if (rule.startsWith('range:')) {
        const [min, max] = rule.slice(6).split(':').map(Number);
        return typeof value === 'number' && value >= min && value <= max;
    }
    switch (rule) {
        case 'bool': return typeof value === 'boolean';
        case 'number': return typeof value === 'number';
        case 'text': return typeof value === 'string';
        case 'list': return Array.isArray(value);
        case 'object':
        case 'palette': return record(value);
        default: return rule === 'json';
    }
}

export function selectConfig(value: unknown, site = false): ConfigDocument {
    const result: ConfigDocument = {};
    if (!record(value)) return result;
    for (const [group, rules] of Object.entries(policy.groups)) {
        const input = value[group];
        if (!record(input)) continue;
        const fields: ConfigGroup = {};
        for (const [key, rule] of Object.entries(rules)) {
            if (site && policy.personalOnly.includes(group + '.' + key)) continue;
            const field = portable(input[key]);
            if (field !== undefined && matches(rule, field)) fields[key] = field;
        }
        if (Object.keys(fields).length) result[group] = fields;
    }
    return result;
}

export function overlayConfig(...documents: (ConfigDocument | null | undefined)[]): ConfigDocument {
    const result: ConfigDocument = {};
    for (const document of documents) {
        for (const [group, fields] of Object.entries(document ?? {})) {
            result[group] = { ...result[group], ...fields };
        }
    }
    return selectConfig(result);
}

export function changedConfig(previous: ConfigDocument, current: ConfigDocument): ConfigDocument {
    const result: ConfigDocument = {};
    for (const [group, fields] of Object.entries(current)) {
        const changed = Object.entries(fields).filter(([name, value]) =>
            JSON.stringify(previous[group]?.[name]) !== JSON.stringify(value));
        if (changed.length) result[group] = Object.fromEntries(changed);
    }
    return result;
}
