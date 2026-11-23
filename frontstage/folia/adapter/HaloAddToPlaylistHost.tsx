import { lazy, Suspense, useEffect } from 'react';
import { useAddToPlaylistStore } from '../stores/useAddToPlaylistStore';
import { selectDisplaySong, usePlaybackStore } from '../stores/usePlaybackStore';
import { canSaveHaloTrack, libraryHref, type LibraryNavigate } from './haloApi';
import { playlistTrackChanges } from './haloLibraryApi';
import { haloSessionChanges } from './HaloAccount';

const Picker = lazy(() => import('./HaloPlaylistPicker'));

export default function HaloAddToPlaylistHost({ isDaylight, onLibrary }: { isDaylight: boolean; onLibrary: LibraryNavigate }) {
    const shown = usePlaybackStore(selectDisplaySong);
    const isOpen = useAddToPlaylistStore(state => state.isOpen);
    const songs = useAddToPlaylistStore(state => state.songs);
    const close = useAddToPlaylistStore(state => state.close);
    const available = !!shown && canSaveHaloTrack(shown);
    useEffect(() => {
        useAddToPlaylistStore.getState().setAvailability({ isApplicable: available, canAdd: available });
    }, [available]);
    useEffect(() => {
        haloSessionChanges.addEventListener('identitychange', close);
        return () => haloSessionChanges.removeEventListener('identitychange', close);
    }, [close]);
    if (!isOpen) return null;
    return <Suspense fallback={null}>
        <Picker requested={songs ?? (shown ? [shown] : [])} isDaylight={isDaylight} onClose={close}
            onReview={playlist => {
                close();
                const target = { tab: 'playlists', playlist } as const;
                if (location.pathname + location.search === libraryHref(target)) {
                    playlistTrackChanges.dispatchEvent(new CustomEvent('change', { detail: playlist }));
                } else {
                    onLibrary(target);
                }
            }} />
    </Suspense>;
}
