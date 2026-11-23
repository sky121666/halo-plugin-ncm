import { lazy, useEffect, useRef, useState } from 'react';
import PagePonderTouchButton from '../components/ponder/PagePonderTouchButton';
import { usePagePonderShortcut } from '../components/ponder/hold/usePagePonderShortcut';
import { usePonderStore } from '../stores/usePonderStore';
import type { Theme } from '../types';
import { DeferredSurface } from './DeferredSurface';

const PonderSurface = lazy(() => import('../components/ponder/PonderHost').then(module => ({ default: module.PonderSurface })));

export function HaloPonder({ theme, isDaylight }: { theme: Theme; isDaylight: boolean }) {
    const wipeRef = useRef<HTMLDivElement | null>(null);
    const labelRef = useRef<HTMLSpanElement | null>(null);
    const holdLabelRef = useRef<HTMLSpanElement | null>(null);
    const cellRef = useRef<HTMLSpanElement | null>(null);
    const progress = { wipeRef, labelRef, holdLabelRef, cellRef };
    const pageHold = usePagePonderShortcut(progress);
    const isOpen = usePonderStore(state => state.session !== null || state.isNavigationOpen);
    const [hoveredTarget, setHoveredTarget] = useState(false);

    useEffect(() => {
        if (hoveredTarget) return;
        const warmOnTarget = (event: PointerEvent) => {
            if (event.pointerType === 'mouse' && event.target instanceof Element && event.target.closest('[data-ponder]')) {
                setHoveredTarget(true);
            }
        };
        window.addEventListener('pointerover', warmOnTarget, { passive: true });
        return () => window.removeEventListener('pointerover', warmOnTarget);
    }, [hoveredTarget]);

    return <>
        <PagePonderTouchButton theme={theme} isDaylight={isDaylight} />
        <DeferredSurface active={isOpen || pageHold.isHolding || hoveredTarget} label="交互指南" silent={!isOpen}>
            <PonderSurface theme={theme} isDaylight={isDaylight} progress={progress} pageHold={pageHold} />
        </DeferredSurface>
    </>;
}
