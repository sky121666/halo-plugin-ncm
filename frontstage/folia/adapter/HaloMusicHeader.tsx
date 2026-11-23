import { Library, Music2, Search } from 'lucide-react';
import { haloSettings, musicHome, musicLibrary } from './haloApi';
import './halo.css';

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
            }}><Music2 size={19} /> {haloSettings.title}</a>
            <nav aria-label="音乐导航">
                {haloSettings.search && <button type="button" className="halo-gallery-action" onClick={onSearch}>
                    <Search size={17} /> 点唱台
                </button>}
                <HaloLibraryLink onLibrary={onLibrary} current={libraryActive} />
                <a href="/">返回博客</a>
            </nav>
        </header>
    );
}
