import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { LoaderCircle, LogIn, UserRound } from 'lucide-react';
import { PlatformMark } from '../components/login/PlatformMark';
import { useOnlineProviderAccountStore } from '../stores/useOnlineProviderAccountStore';
import { omni } from '../services/onlineMusic/omni';
import { libraryApi, LibraryRequestError, readOwnMusic } from './haloLibraryApi';
import { foliaUser, setFoliaBinding } from './haloFoliaBinding';
import type { NcmAccountView } from './generated/ncm-account-view';
import type { NcmMeProfile } from './generated/ncm-me-profile';
import { HaloAuthDialog } from './HaloAuthDialog';
import { useHaloMusicAiAvailability } from './haloMusicAi';
import { HaloBindingDialog } from './HaloBindingDialog';
import { providerLabel, visibleBounds } from './HaloBindingMotion';

type AccountState =
    | { kind: 'loading' }
    | { kind: 'guest' }
    | { kind: 'account'; account: NcmAccountView }
    | { kind: 'error'; error: unknown };
type AccountDialog = 'login' | 'binding';
type DialogOrigin = {
    mode: AccountDialog;
    control: HTMLElement;
    group: Element | null;
    home: boolean;
    resumeBinding?: boolean;
};

const AccountContext = createContext<{
    state: AccountState;
    retry: () => void;
    openDialog: (mode: AccountDialog, control: HTMLElement) => void;
    bindingSheet: ReactNode;
} | null>(null);
export const haloSessionChanges = new EventTarget();

function updateFoliaBinding(account?: NcmAccountView) {
    const previous = foliaUser();
    if (!setFoliaBinding(account)) return;
    if (previous && account) haloSessionChanges.dispatchEvent(new Event('identitychange'));
    useOnlineProviderAccountStore.getState().clearAccount('netease');
    void omni.readAccount('netease', 'sign-in');
}

async function readBinding(signal: AbortSignal): Promise<NcmAccountView> {
    const account = await libraryApi.binding(signal);
    if (!account.bound || account.readable === false || account.userId) return account;
    try {
        const profile = await readOwnMusic<NcmMeProfile>('profile', {}, signal);
        const userId = Number(profile.userId);
        return Number.isSafeInteger(userId) && userId > 0 ? { ...account, userId } : { ...account, readable: false };
    } catch (error) {
        if (error instanceof LibraryRequestError && error.status === 403) return { ...account, readable: false };
        throw error;
    }
}

function accountControl(source: DialogOrigin | undefined, boundOnly: boolean) {
    if (!source) return null;
    if (!boundOnly && visibleBounds(source.control)) return source.control;

    const selector = boundOnly ? '[data-halo-account="bound"]' : '[data-halo-account]';
    const groupControl = source.group?.querySelector<HTMLElement>(selector);
    if (groupControl && visibleBounds(groupControl)) return groupControl;
    if (!source.home) return null;

    const bravais = boundOnly ? '[data-bravais-account="signed-in"] [data-bravais-account-toggle], [data-bravais-account-toggle="compact"]' :
        '[data-bravais-account-toggle]';
    const controls = document.querySelectorAll<HTMLElement>(`[data-halo-home-account] ${selector}, ${bravais}`);
    return Array.from(controls).find(control => visibleBounds(control)) ?? null;
}

export function HaloAccountProvider({ children }: { children: ReactNode }) {
    const [state, setState] = useState<AccountState>({ kind: 'loading' });
    useHaloMusicAiAvailability(state.kind === 'account');
    const [dialog, setDialog] = useState<AccountDialog>();
    const origin = useRef<DialogOrigin | undefined>(undefined);
    const bindingSession = useRef(0);
    const request = useRef<AbortController | null>(null);
    const lastSignedIn = useRef<boolean | undefined>(undefined);
    const lastCheck = useRef(0);

    const closeDialog = useCallback(() => {
        const previous = origin.current;
        origin.current = undefined;
        setDialog(undefined);
        window.requestAnimationFrame(() => {
            if (origin.current) return;
            accountControl(previous, false)?.focus({ preventScroll: true });
        });
    }, []);

    const openDialog = useCallback((mode: AccountDialog, control: HTMLElement) => {
        if (origin.current) return;
        origin.current = {
            mode, control,
            group: control.closest('.halo-account-controls'),
            home: Boolean(control.closest('[data-halo-home-account], .bravais-account-switcher')),
        };
        if (mode === 'binding') bindingSession.current += 1;
        setDialog(mode);
    }, []);

    const read = useCallback(async (): Promise<AccountState | undefined> => {
        request.current?.abort();
        const controller = new AbortController();
        request.current = controller;
        lastCheck.current = Date.now();
        let next: AccountState;
        try {
            next = { kind: 'account', account: await readBinding(controller.signal) };
        } catch (error) {
            next = error instanceof LibraryRequestError && error.status === 401
                ? { kind: 'guest' } : { kind: 'error', error };
        }
        if (controller.signal.aborted) return undefined;
        request.current = null;
        if (next.kind === 'account' || next.kind === 'guest') {
            const signedIn = next.kind === 'account';
            if (lastSignedIn.current && !signedIn) haloSessionChanges.dispatchEvent(new Event('identitychange'));
            lastSignedIn.current = signedIn;
        }
        updateFoliaBinding(next.kind === 'account' ? next.account : undefined);
        setState(next);
        if (origin.current?.mode === 'login' && next.kind === 'account') {
            if (origin.current.resumeBinding) {
                origin.current.mode = 'binding';
                origin.current.resumeBinding = false;
                bindingSession.current += 1;
                setDialog('binding');
            } else {
                closeDialog();
            }
        }
        return next;
    }, [closeDialog]);
    const retry = useCallback(() => {
        setState({ kind: 'loading' });
        void read();
    }, [read]);

    useEffect(() => {
        let timer: number | undefined;
        const recheck = () => {
            window.clearTimeout(timer);
            if (document.visibilityState !== 'visible') return;
            timer = window.setTimeout(() => {
                if (document.visibilityState === 'visible') void read();
            }, Math.max(0, 1500 - (Date.now() - lastCheck.current)));
        };
        const onShow = (event: PageTransitionEvent) => { if (event.persisted) recheck(); };
        void read();
        document.addEventListener('visibilitychange', recheck);
        window.addEventListener('focus', recheck);
        window.addEventListener('pageshow', onShow);
        return () => {
            request.current?.abort();
            window.clearTimeout(timer);
            document.removeEventListener('visibilitychange', recheck);
            window.removeEventListener('focus', recheck);
            window.removeEventListener('pageshow', onShow);
        };
    }, [read]);

    const closeAndRefresh = useCallback(() => {
        closeDialog();
        void read();
    }, [closeDialog, read]);

    function acceptBinding(account: NcmAccountView) {
        request.current?.abort();
        lastSignedIn.current = true;
        updateFoliaBinding(account);
        setState({ kind: 'account', account });
    }

    const resolveLanding = useCallback(() => accountControl(origin.current, true), []);

    function reauthenticateBinding() {
        if (!origin.current) return;
        origin.current.mode = 'login';
        origin.current.resumeBinding = true;
        setDialog('login');
    }

    const bindingSheet = <HaloBindingDialog key={bindingSession.current} isOpen={dialog === 'binding'}
        trigger={origin.current?.control} account={state.kind === 'account' ? state.account : undefined}
        onBound={acceptBinding} onLogin={reauthenticateBinding} onClose={closeAndRefresh} resolveLanding={resolveLanding} />;
    return <AccountContext.Provider value={{ state, retry, openDialog, bindingSheet }}>
        {children}
        {dialog === 'login' && <HaloAuthDialog verify={async () => (await read())?.kind === 'account'} onClose={closeAndRefresh} />}
    </AccountContext.Provider>;
}

export function HaloBindingHost() {
    return useHaloAccount().bindingSheet;
}

export function useHaloAccount() {
    const value = useContext(AccountContext);
    if (!value) throw new Error('HaloAccountProvider is missing');
    return value;
}

export function HaloAccountAction({ variant }: { variant?: 'panel' }) {
    const { state, retry, openDialog } = useHaloAccount();
    const className = 'halo-account-action' + (variant ? ' halo-account-action--' + variant : '');
    if (state.kind === 'loading') return <div className="halo-account-controls">
        <span className={className} role="status" data-halo-account="loading">
            <LoaderCircle size={16} aria-hidden="true" /><span>读取账号</span>
        </span>
    </div>;
    if (state.kind === 'guest') return <div className="halo-account-controls">
        <button type="button" className={className} data-halo-account="guest" aria-haspopup="dialog"
            onClick={event => openDialog('login', event.currentTarget)}>
            <LogIn size={16} aria-hidden="true" /><span>登录 Halo</span>
        </button>
    </div>;
    if (state.kind === 'error') return <div className="halo-account-controls">
        <button type="button" className={className} onClick={retry}
            data-halo-account="error" title="账号状态暂时无法读取">
            <UserRound size={16} aria-hidden="true" /><span>重试账号</span>
        </button>
    </div>;

    const { account } = state;
    if (account.bound && account.readable !== false) {
        const name = account.nickname?.trim() || '网易云账号';
        return <div className="halo-account-controls">
            <span className={className + ' halo-account-identity'} data-halo-account="bound" title={name} tabIndex={-1}>
                {account.avatarUrl ? <img data-halo-binding-mark src={account.avatarUrl} alt="" width="22" height="22" />
                    : <span data-halo-binding-mark className="inline-flex shrink-0"><UserRound size={16} aria-hidden="true" /></span>}
                <span className="halo-account-name">{name}</span>
                {account.managedInConsole && variant !== 'panel' && <span className="halo-account-scope">站长</span>}
            </span>
        </div>;
    }

    return <div className="halo-account-controls">
        <button type="button" className={className} aria-haspopup="dialog"
            data-halo-account={account.bound ? 'unreadable' : 'unbound'}
            onClick={event => openDialog('binding', event.currentTarget)}>
            <span data-halo-binding-mark className="inline-flex shrink-0">
                <PlatformMark providerId="netease" label={providerLabel} size={16} />
            </span>
            <span className="halo-account-name">{account.bound ? '重新绑定网易云' : '绑定网易云'}</span>
        </button>
    </div>;
}
