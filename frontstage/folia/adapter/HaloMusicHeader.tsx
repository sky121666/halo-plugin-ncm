import { House, Library, Music2, Search, SlidersHorizontal } from 'lucide-react';
import { haloSettings, musicHome, musicLibrary } from './haloApi';
import { useHaloConfig } from './HaloConfigProvider';
import { owningFoliaFrame } from './playbackSession';
import './halo.css';

const iconLinkClass = 'inline-flex shrink-0 items-center rounded-full p-2 opacity-60 transition hover:bg-white/10 hover:opacity-100 focus-visible:opacity-100 focus-visible:outline focus-visible:outline-2';

export function HaloMusicPageIcon({ className }: { className: string }) {
    return haloSettings.icon ? <img src={haloSettings.icon} alt="" className={className} /> : null;
}

export function HaloHomeSettingsLink() {
    const { state } = useHaloConfig();
    if (state.auth !== 'account' || state.viewer?.canPublishSite !== true) return null;
    return <a href="/console/ncm?tab=music-page"
        target="_blank" rel="noopener" aria-label="管理音乐首页" title="管理音乐首页"
        className={iconLinkClass}>
        <SlidersHorizontal size={18} aria-hidden="true" />
    </a>;
}

export function HaloBlogLink({ iconOnly = false }: { iconOnly?: boolean }) {
    const frame = owningFoliaFrame();
    return <a href="/" target={frame ? '_top' : undefined} aria-label="返回博客" title="返回博客"
        className={iconOnly ? iconLinkClass : undefined}
        onClick={event => {
            if (!frame?.ncmReturnToBlog || event.defaultPrevented || event.button !== 0
                || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            frame.ncmReturnToBlog();
        }}>
        {iconOnly ? <House size={18} aria-hidden="true" /> : '返回博客'}
    </a>;
}

export function HaloLibraryLink({ onLibrary, current = false }: { onLibrary?: () => void; current?: boolean }) {
    return <a href={musicLibrary} aria-current={current ? 'page' : undefined}
        className="halo-library-link" onClick={event => {
            if (onLibrary && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
                event.preventDefault();
                if (!current) onLibrary();
            }
        }}><Library size={17} aria-hidden="true" /> 个人曲库</a>;
}

export function HaloMusicHeader({ onHome, onSearch, onLibrary, libraryActive }: {
    onHome?: () => void;
    onSearch: () => void;
    onLibrary?: () => void;
    libraryActive?: boolean;
}) {
    return (
        <header className="halo-gallery-header">
            <a href={musicHome} className="halo-gallery-brand" onClick={event => {
                if (onHome && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
                    event.preventDefault();
                    onHome();
                }
            }}>
                {haloSettings.icon
                    ? <HaloMusicPageIcon className="size-5 shrink-0 rounded object-contain" />
                    : <Music2 size={19} aria-hidden="true" />}
                {haloSettings.title}
            </a>
            <nav aria-label="音乐导航">
                {haloSettings.search && <button type="button" className="halo-gallery-action" onClick={onSearch}>
                    <Search size={17} /> 点唱台
                </button>}
                <HaloLibraryLink onLibrary={onLibrary} current={libraryActive} />
                <HaloBlogLink />
            </nav>
        </header>
    );
}
