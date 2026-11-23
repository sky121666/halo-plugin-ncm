import { useEffect, useRef, useState } from 'react';
import { bindingPath } from './haloLibraryApi';
import { HaloDialog } from './HaloDialog';

type Phase = 'loading' | 'ready' | 'checking' | 'load-error' | 'check-error';
const nativeAuthPath = /^\/(?:login|signup|complete-profile|password-reset|challenges|oauth2)(?:\/|$)/;
const frameUrl = '/login?redirect_uri=' + encodeURIComponent(bindingPath);

const compactForm = `
    html, body { margin: 0 !important; min-height: 0 !important; }
    .auth-page-split { display: block !important; min-height: 0 !important; padding: 0 !important; }
    .auth-page-split > :not(.auth-form-section-split) { display: none !important; }
    .auth-form-section-split {
        width: 100% !important;
        max-width: none !important;
        min-height: 0 !important;
        margin: 0 !important;
        padding: 0 !important;
        border: 0 !important;
        border-radius: 0 !important;
        box-shadow: none !important;
    }
    main.auth-form-container { width: 100% !important; padding: 1rem !important; }
    html[data-halo-auth-layout="password"] .auth-form-container :is(.auth-title, .auth-subtitle) {
        display: none !important;
    }
    html[data-halo-auth-layout="password"] #login-form { display: grid; gap: .875rem; }
    html[data-halo-auth-layout="password"] #login-form > * { margin-block: 0 !important; }
`;

function prepareNativeForm(doc: Document, onClose: () => void) {
    if (doc.querySelector('.auth-page-split main.auth-form-container')) {
        if (doc.querySelector('#login-form #plainPassword')) {
            doc.documentElement.dataset.haloAuthLayout = 'password';
        }
        const styles = doc.createElement('style');
        styles.textContent = compactForm;
        doc.head.append(styles);
    }
    // 第三方身份提供方由 Halo 发起，独立标签页承接其认证页面。
    doc.querySelectorAll<HTMLFormElement>('form[action^="/login/social/"]').forEach(form => {
        form.target = '_blank';
        form.rel = 'noopener';
    });
    const onKey = (event: KeyboardEvent) => {
        if (event.key !== 'Escape' || event.defaultPrevented) return;
        event.preventDefault();
        onClose();
    };
    const onClick = (event: MouseEvent) => {
        const target = event.target as Element | null;
        const link = target?.closest?.<HTMLAnchorElement>('a[href]');
        if (link?.href === window.location.origin + '/') {
            event.preventDefault();
            onClose();
        }
    };
    doc.addEventListener('keydown', onKey);
    doc.addEventListener('click', onClick);
    return () => {
        doc.removeEventListener('keydown', onKey);
        doc.removeEventListener('click', onClick);
    };
}

export function HaloAuthDialog({ verify, onClose }: {
    verify: () => Promise<boolean>;
    onClose: () => void;
}) {
    const frame = useRef<HTMLIFrameElement>(null);
    const cleanFrame = useRef<(() => void) | undefined>(undefined);
    const [phase, setPhase] = useState<Phase>('loading');
    const [attempt, setAttempt] = useState(0);

    useEffect(() => () => { cleanFrame.current?.(); }, []);
    useEffect(() => {
        if (phase !== 'loading') return;
        const timer = window.setTimeout(() => setPhase('load-error'), 15000);
        return () => window.clearTimeout(timer);
    }, [phase, attempt]);

    async function confirmSession() {
        setPhase('checking');
        if (!await verify()) setPhase('check-error');
    }

    function frameLoaded() {
        cleanFrame.current?.();
        cleanFrame.current = undefined;
        const doc = frame.current?.contentDocument;
        if (!doc?.location) {
            setPhase('load-error');
            return;
        }
        const path = doc.location.pathname;
        if (doc.location.protocol === 'about:') return;
        // 登录校验失败后的原生表单重试也可能回退到 Halo 个人中心。
        // 只通过实际绑定接口的会话结果完成弹窗，页面地址仅用于识别提交结束。
        if (!nativeAuthPath.test(path)) {
            void confirmSession();
            return;
        }
        cleanFrame.current = prepareNativeForm(doc, onClose);
        setPhase('ready');
    }

    const failure = phase === 'load-error' || phase === 'check-error';
    return <HaloDialog title="登录 Halo" onClose={onClose}>
        <div className="halo-auth-body" data-halo-auth-phase={phase}>
            <iframe key={attempt} ref={frame} className="halo-auth-frame" title="登录 Halo" src={frameUrl}
                sandbox="allow-forms allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox"
                onLoad={frameLoaded} onError={() => setPhase('load-error')} />
            {phase !== 'ready' && <div className="halo-auth-status" role={failure ? 'alert' : 'status'}>
                <p>{phase === 'loading' ? '正在打开认证表单…' : phase === 'checking' ? '正在确认账号状态…' :
                    phase === 'load-error' ? '认证表单暂时无法加载。' : '暂时无法确认账号状态。'}</p>
                {failure && <button type="button" className="halo-account-action" onClick={() => {
                    if (phase === 'check-error') void confirmSession();
                    else {
                        setPhase('loading');
                        setAttempt(value => value + 1);
                    }
                }}>重试</button>}
            </div>}
        </div>
    </HaloDialog>;
}
