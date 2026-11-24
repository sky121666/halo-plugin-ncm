import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { useHaloAccount } from './HaloAccount';
import type { FoliaThemeDriver } from './foliaConfigDriver';
import { HaloConfigSync, type ConfigState } from './haloConfigSync';
import { setHaloDiagnosticAccess } from './haloDiagnostics';

const Context = createContext<HaloConfigSync | null>(null);
const unmounted: ConfigState = { auth: 'guest', enabled: false, status: 'local', siteStatus: 'loading', siteDirty: false };
const noSubscription = () => () => {};
const initial = () => unmounted;

export function HaloConfigProvider({ children }: { children: ReactNode }) {
    const { state: account } = useHaloAccount();
    const [service] = useState(() => new HaloConfigSync());

    useEffect(() => {
        const auth = () => void service.checkAccount(account.kind);
        auth();
        window.addEventListener('online', auth);
        return () => window.removeEventListener('online', auth);
    }, [account, service]);

    useEffect(() => {
        void service.loadSite(true);
        const permissions = () => setHaloDiagnosticAccess(service.status().viewer?.canViewDiagnostics === true);
        const unsubscribe = service.subscribe(permissions);
        window.addEventListener('pagehide', service.flushChanges);
        return () => {
            unsubscribe();
            setHaloDiagnosticAccess(false);
            window.removeEventListener('pagehide', service.flushChanges);
            service.dispose();
        };
    }, [service]);

    return <Context.Provider value={service}>{children}</Context.Provider>;
}

export function useHaloConfig() {
    const service = useContext(Context);
    const state = useSyncExternalStore(service?.subscribe ?? noSubscription, service?.status ?? initial);
    return { service, state };
}

export function useFoliaConfigDriver(theme: FoliaThemeDriver) {
    const service = useContext(Context);
    const current = useRef(theme);
    current.current = theme;
    useLayoutEffect(() => {
        if (!service) return;
        let cleanup: (() => void) | undefined;
        let cancelled = false;
        // Normal Folia initialization stays on the main bundle. The visual import codec is loaded
        // with the sync adapter and reused for all portable settings.
        void import('./foliaConfigDriver').then(({ createFoliaConfigDriver }) => {
            if (cancelled) return;
            cleanup = service.attach(createFoliaConfigDriver(() => current.current));
        });
        return () => {
            cancelled = true;
            cleanup?.();
        };
    }, [service]);
    useEffect(() => service?.changed(), [theme.bgMode, theme.customTheme, service]);
}
