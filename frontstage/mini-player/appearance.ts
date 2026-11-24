export type SiteColorScheme = 'light' | 'dark' | 'auto';
export type MusicColorPreference = 'site' | 'system' | 'light' | 'dark';

const siteKey = 'ncm:site-color-scheme:v1';
const musicKey = 'ncm:music-color-scheme:v1';
const siteAttribute = 'data-ncm-site-color-scheme';
const darkQuery = '(prefers-color-scheme: dark)';

function readLocal(key: string): string | null {
    try { return localStorage.getItem(key); } catch { return null; }
}

function writeLocal(key: string, value: string) {
    try {
        if (localStorage.getItem(key) !== value) localStorage.setItem(key, value);
    } catch {
        // Appearance still works when browser storage is disabled.
    }
}

function siteColorScheme(value: unknown): SiteColorScheme | undefined {
    return value === 'light' || value === 'dark' || value === 'auto' ? value : undefined;
}

export function colorSchemeFromCss(value: string): SiteColorScheme | undefined {
    const tokens = value.trim().split(/\s+/);
    const light = tokens.includes('light');
    const dark = tokens.includes('dark');
    return light && dark ? 'auto' : light ? 'light' : dark ? 'dark' : undefined;
}

export function themeColorScheme(style: CSSStyleDeclaration): SiteColorScheme | undefined {
    return colorSchemeFromCss(style.getPropertyValue('--_ncm-halo-scheme'))
        ?? colorSchemeFromCss(style.getPropertyValue('--_ncm-class-scheme'))
        ?? colorSchemeFromCss(style.getPropertyValue('--_ncm-legacy-scheme'));
}

export function musicColorPreference(value: unknown): MusicColorPreference | undefined {
    return value === 'site' || value === 'system' || value === 'light' || value === 'dark' ? value : undefined;
}

export function legacyColorPreference(followSystem: unknown, daylight: unknown): MusicColorPreference | undefined {
    if (followSystem === true) return 'system';
    return typeof daylight === 'boolean' ? (daylight ? 'light' : 'dark') : undefined;
}

export function readMusicColorPreference(): MusicColorPreference {
    const daylight = readLocal('default_theme_daylight');
    return musicColorPreference(readLocal(musicKey))
        ?? legacyColorPreference(readLocal('follow_system_theme') === 'true',
            daylight === 'true' ? true : daylight === 'false' ? false : undefined)
        ?? 'site';
}

export function saveMusicColorPreference(preference: MusicColorPreference) {
    writeLocal(musicKey, preference);
}

function embeddedBlog() {
    if (typeof window === 'undefined') return null;
    const frame = window.frameElement;
    return frame?.hasAttribute('data-ncm-folia-frame') ? frame.ownerDocument.documentElement : null;
}

function systemAppearance() {
    // An iframe's own media query can reflect the embedding element's color scheme.
    // A user who selected “system” should see the OS preference of the top document.
    if (typeof window !== 'undefined') {
        return (embeddedBlog()?.ownerDocument.defaultView ?? window).matchMedia?.(darkQuery);
    }
}

export function resolveMusicColorScheme(preference: MusicColorPreference): 'light' | 'dark' {
    if (preference === 'light' || preference === 'dark') return preference;
    const site = preference === 'site'
        ? siteColorScheme(embeddedBlog()?.getAttribute(siteAttribute)) ?? siteColorScheme(readLocal(siteKey))
        : undefined;
    if (site === 'light' || site === 'dark') return site;
    return systemAppearance()?.matches ? 'dark' : 'light';
}

export function observeMusicColorScheme(update: () => void): () => void {
    const media = systemAppearance();
    const blog = embeddedBlog();
    const observer = new MutationObserver(update);
    if (blog) observer.observe(blog, {attributeFilter: [siteAttribute]});
    const onStorage = (event: StorageEvent) => {
        if (event.key === siteKey || event.key === null) update();
    };
    media?.addEventListener('change', update);
    window.addEventListener('storage', onStorage);
    window.addEventListener('pageshow', update);
    update();
    return () => {
        observer.disconnect();
        media?.removeEventListener('change', update);
        window.removeEventListener('storage', onStorage);
        window.removeEventListener('pageshow', update);
    };
}

function publishSiteColorScheme() {
    const root = document.documentElement;
    const body = document.body;
    const preview = new URLSearchParams(location.search).has('preview-theme');
    let sawDarkClass = false;
    const update = () => {
        const style = getComputedStyle(body ?? root);
        sawDarkClass ||= root.classList.contains('dark') || body?.classList.contains('dark') === true;
        const scheme = themeColorScheme(style) ?? colorSchemeFromCss(style.colorScheme)
            ?? (sawDarkClass ? 'light' : 'auto');
        if (root.getAttribute(siteAttribute) === scheme) return;
        root.setAttribute(siteAttribute, scheme);
        if (!preview) writeLocal(siteKey, scheme);
    };
    const observer = new MutationObserver(update);
    const attributes = ['data-color-scheme', 'data-theme', 'class', 'style'];
    observer.observe(root, {attributeFilter: attributes});
    if (body) observer.observe(body, {attributeFilter: attributes});
    window.matchMedia(darkQuery).addEventListener('change', update);
    window.addEventListener('pageshow', update);
    document.addEventListener('swup:page:view', update);
    update();
}

if (typeof document !== 'undefined' && document.querySelector('script[data-ncm-appearance-source]')) {
    publishSiteColorScheme();
}
