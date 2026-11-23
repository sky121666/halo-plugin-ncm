import { useMemo } from 'react';
import SearchResultRow from '../components/app/search/SearchResultRow';
import { EXPLORE_TOKENS } from '../components/app/home/explore/ExploreSections';
import '../components/app/search/search.css';
import type { SongResult } from '../types';
import type { MediaId, OmniCollection } from '../types/onlineMusic';
import { HaloMusicHeader } from './HaloMusicHeader';
import { canSaveHaloTrack, toCollection, toSong, type HaloDirectEntry } from './haloApi';
import { openAddSongsToPlaylist } from '../stores/useAddToPlaylistStore';

type Props = {
    entry: HaloDirectEntry;
    onHome?: () => void;
    onSearch: () => void;
    onLibrary?: () => void;
    onOpen: (collection: OmniCollection) => void;
    onPlay: (song: SongResult, queue: SongResult[]) => void;
    onAdd: (song: SongResult) => void;
};

const rowStyle = {};

export function HaloDirectPage({ entry, onHome, onSearch, onLibrary, onOpen, onPlay, onAdd }: Props) {
    const track = useMemo(() => entry.type === 'song' ? toSong(entry.item) : null, [entry]);
    const open = (type: 'artist' | 'album', name: string, id?: MediaId) => {
        if (id == null || !/^\d+$/.test(String(id))) return;
        onOpen(toCollection({ id: String(id), name }, type));
    };
    return (
        <div className="halo-gallery" data-halo-entry={entry.type} style={EXPLORE_TOKENS}>
            <div className="halo-gallery-inner">
                <HaloMusicHeader onHome={onHome} onSearch={onSearch} onLibrary={onLibrary} />
                <main className="halo-entry-main">
                    {track && <div className="search-workspace halo-entry-song">
                        <SearchResultRow
                            track={track}
                            style={rowStyle}
                            index={0}
                            detail
                            onPlayTrack={song => onPlay(song, [song])}
                            onAddTrackToQueue={onAdd}
                            canSaveTrackToPlaylist={canSaveHaloTrack}
                            onSaveTrackToPlaylist={song => openAddSongsToPlaylist([song])}
                            onOpenArtist={(_, name, id) => open('artist', name, id)}
                            onOpenAlbum={(_, name, id) => open('album', name, id)}
                        />
                    </div>}
                    {entry.type === 'error' && <div role="status">
                        <h1>{entry.item.name}</h1>
                        <p>{entry.item.message}</p>
                        <button type="button" className="halo-gallery-action" onClick={() => location.reload()}>重试</button>
                    </div>}
                </main>
            </div>
        </div>
    );
}
