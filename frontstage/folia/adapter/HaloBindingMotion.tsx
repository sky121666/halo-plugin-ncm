import { useEffect, useLayoutEffect, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion, useIsPresent } from 'framer-motion';
import { ShieldCheck } from 'lucide-react';
import { AccountPicture, PlatformMark } from '../components/login/PlatformMark';
import { LoginTile } from '../components/login/window/LoginTile';
import type { LoginScene } from '../components/login/window/loginView';
import { settingsTokens, useSettingsKitTheme } from '../components/modal/settings/kit/kitTheme';
import { KIT_EASE, kitTransition, useKitReducedMotion } from '../components/modal/settings/kit/motion';
import type { ProviderUser } from '../types/onlineMusic';

export const providerLabel = '网易云音乐';
export const successAvatarSize = 80;
export type BindingPhase = 'loading' | 'waiting' | 'scanned' | 'authorized' | 'saving'
    | 'reviewing' | 'expired' | 'failed' | 'success';

const scenes: Record<BindingPhase, LoginScene> = {
    loading: 'preparing',
    waiting: 'code',
    scanned: 'scanned',
    authorized: 'scanned',
    saving: 'reading',
    reviewing: 'checking',
    expired: 'failed',
    failed: 'failed',
    success: 'account',
};

type TileProps = {
    phase: BindingPhase;
    image?: string;
    user?: ProviderUser;
    avatarRef: RefObject<HTMLSpanElement | null>;
    onRefresh?: () => void;
};

function TileScene({ phase, image = '', user, avatarRef, onRefresh }: TileProps) {
    const reduced = useKitReducedMotion();
    const present = useIsPresent();
    const code = phase === 'waiting';
    const picture = phase === 'authorized' ? (
        <span className="halo-binding-authorization" aria-hidden="true">
            <ShieldCheck size={34} />
        </span>
    ) : user && phase === 'success' ? (
        <span ref={avatarRef} className="inline-flex">
            <AccountPicture user={user} size={successAvatarSize} providerId="netease" />
        </span>
    ) : null;

    return <motion.div className="absolute inset-0" inert={!present}
        data-halo-binding-picture={phase}
        role={code ? 'img' : undefined}
        aria-label={code ? '网易云音乐绑定二维码' : undefined}
        initial={reduced ? false : { opacity: 0, scale: code ? 1 : 0.94 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: reduced ? 1 : code ? 0.88 : 0.96 }}
        transition={kitTransition('move', reduced)}>
        <LoginTile scene={scenes[phase]} providerId="netease" label={providerLabel} inBrowser={false}
            codeImage={image} user={user ?? null} failedKind={phase === 'expired' ? 'A' : 'B'} pendingText=""
            onRefreshCode={onRefresh} refreshLabel="重新获取二维码"
            doneLayer={picture ? () => picture : undefined} />
    </motion.div>;
}

export function BindingTile(props: TileProps) {
    const present = useIsPresent();
    return <div className="halo-binding-tile">
        {present && props.phase === 'waiting' && <svg className="login-rings halo-binding-rings" aria-hidden="true" width="1" height="1">
            <circle r="104" /><circle r="104" />
        </svg>}
        <AnimatePresence initial={false}>
            <TileScene key={props.phase} {...props} />
        </AnimatePresence>
    </div>;
}

type Bounds = Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>;
export type BindingTravel = {
    from: Bounds;
    to: Bounds;
    target: HTMLElement;
};

export function visibleBounds(element?: Element | null) {
    if (!element?.isConnected || element.checkVisibility?.({ checkOpacity: true, checkVisibilityCSS: true }) === false) return null;
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0
        && rect.right > 0 && rect.bottom > 0 && rect.left < window.innerWidth && rect.top < window.innerHeight
        ? rect : null;
}

export function bindingMark(control?: HTMLElement | null) {
    return control?.querySelector<HTMLElement>('[data-halo-binding-mark], [data-login-pill], .bravais-account-avatar, img') ?? null;
}

export const spaceForBindingTravel = () => window.innerWidth >= 540 && window.innerHeight >= 460;

export function titleTravel(trigger: HTMLElement, title: HTMLElement, restingHeight: number): BindingTravel | null {
    const from = visibleBounds(bindingMark(trigger));
    const panel = title.closest<HTMLElement>('[role="dialog"]');
    if (!from || !panel || !title.offsetWidth) return null;
    // Sheet is already moving when its title mounts. Measure its resting layout as Folia does.
    let left = (window.innerWidth - panel.offsetWidth) / 2;
    let top = Math.max(16, (window.innerHeight - restingHeight) / 2);
    for (let node: HTMLElement | null = title; node && node !== panel; node = node.offsetParent as HTMLElement | null) {
        left += node.offsetLeft;
        top += node.offsetTop;
    }
    return { from, to: { left, top, width: title.offsetWidth, height: title.offsetHeight }, target: title };
}

export function BindingFlight({ travel, user, onFinish }: {
    travel: BindingTravel;
    user?: ProviderUser;
    onFinish: () => void;
}) {
    const { isDaylight, theme } = useSettingsKitTheme();
    const { from, to, target } = travel;
    useLayoutEffect(() => {
        target.dataset.haloBindingLanding = 'true';
        return () => { delete target.dataset.haloBindingLanding; };
    }, [target]);
    useEffect(() => {
        window.addEventListener('resize', onFinish);
        return () => window.removeEventListener('resize', onFinish);
    }, [onFinish]);

    const picture: ReactNode = user
        ? <AccountPicture user={user} size={from.width} providerId="netease" />
        : <PlatformMark providerId="netease" label={providerLabel} size={from.width} />;
    return createPortal(<motion.div aria-hidden="true" data-halo-binding-flight={user ? 'avatar' : 'mark'}
        className="pointer-events-none fixed z-[300] origin-top-left"
        style={{ ...settingsTokens(isDaylight, theme), left: from.left, top: from.top, width: from.width, height: from.height }}
        initial={{ x: 0, y: 0, scale: 1 }}
        animate={{ x: to.left - from.left, y: to.top - from.top, scale: to.width / from.width }}
        transition={{ duration: user ? 0.36 : 0.42, ease: KIT_EASE.inOut }}
        onAnimationComplete={onFinish}>
        {picture}
    </motion.div>, document.body);
}
