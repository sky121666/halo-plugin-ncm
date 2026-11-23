import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type FormEvent, type KeyboardEvent, type RefObject } from 'react';
import { useIsPresent } from 'framer-motion';
import { Check } from 'lucide-react';
import { PlatformMark, platformMarkOf } from '../components/login/PlatformMark';
import Sheet, { SheetButton } from '../components/shared/Sheet';
import { Segmented } from '../components/modal/settings/kit/Segmented';
import { Status } from '../components/modal/settings/kit/Status';
import { FIELD_CLASS } from '../components/modal/settings/kit/TextField';
import { useKitReducedMotion } from '../components/modal/settings/kit/motion';
import { bindingApi, libraryApi, LibraryRequestError } from './haloLibraryApi';
import type { NcmAccountView } from './generated/ncm-account-view';
import type { NcmLoginResult } from './generated/ncm-login-result';
import type { ProviderUser } from '../types/onlineMusic';
import { BindingFlight, BindingTile, bindingMark, providerLabel, spaceForBindingTravel, titleTravel, visibleBounds,
    type BindingPhase, type BindingTravel } from './HaloBindingMotion';
import '../components/login/login.css';
import './haloBinding.css';

const methods = [
    { value: 'qr', label: '扫码' },
    { value: 'sms', label: '短信' },
    { value: 'password', label: '密码' },
] as const;
type Method = (typeof methods)[number]['value'];
type Work = 'sending' | 'saving' | 'reviewing';
type Problem = { text: string; signIn?: boolean };
type Qr =
    | { phase: 'loading' }
    | { phase: 'expired'; image?: string }
    | { phase: 'waiting' | 'scanned'; key: string; image: string }
    | { phase: 'authorized'; key: string }
    | { phase: 'failed'; problem: Problem };

const bindingSteps = ['扫码', '授权', '绑定'];
const bindingCopy: Record<BindingPhase, { title: string; detail?: string; step: number }> = {
    loading: { title: '正在生成二维码…', step: 0 },
    waiting: { title: '用网易云音乐 App 扫码', detail: '扫码后，在手机上确认授权。', step: 0 },
    scanned: { title: '已扫码', detail: '请在网易云音乐 App 中确认授权。', step: 1 },
    authorized: { title: '手机已授权', detail: '确认后绑定到当前 Halo 账号。', step: 2 },
    saving: { title: '正在绑定…', detail: '正在等待 Halo 保存结果。', step: 2 },
    reviewing: { title: '正在核对绑定…', step: 2 },
    expired: { title: '二维码已过期', detail: '重新获取后继续扫码。', step: 0 },
    failed: { title: '暂时无法继续扫码', step: 0 },
    success: { title: '已绑定网易云', step: 2 },
};
const restingHeight = 360;
const successHoldMs = 510;
const fieldClass = FIELD_CLASS + ' w-full min-w-0 px-2.5 max-sm:min-h-11';
const failures: Record<Work, string> = {
    sending: '暂时无法发送短信验证码，请重试。',
    saving: '暂时无法保存绑定，请重试。',
    reviewing: '暂时无法读取绑定结果，请重试。',
};

function describe(error: unknown, fallback: string): Problem {
    const status = error instanceof LibraryRequestError ? error.status : undefined;
    if (status === 401) return { text: 'Halo 登录已失效，请重新登录。', signIn: true };
    if (status === 403) return { text: '当前 Halo 账号无法提交绑定，请检查登录状态和权限。' };
    if (status === 429) return { text: '请求太频繁，请稍后重试。' };
    if (status === 409) return { text: '绑定状态已变化，请核对账号后重试。' };
    return { text: fallback };
}

type BindingProps = {
    account?: NcmAccountView;
    onBound: (account: NcmAccountView) => void;
    onLogin: () => void;
};

export function HaloBindingDialog({ isOpen, trigger, onClose, onBound, resolveLanding, ...binding }: BindingProps & {
    isOpen: boolean;
    trigger?: HTMLElement;
    onClose: () => void;
    resolveLanding: () => HTMLElement | null;
}) {
    const [work, setWork] = useState<Work>();
    const [successfulUser, setSuccessfulUser] = useState<ProviderUser>();
    const [arrival, setArrival] = useState<BindingTravel | null>(null);
    const [departure, setDeparture] = useState<BindingTravel | null>(null);
    const titleMark = useRef<HTMLSpanElement>(null);
    const avatar = useRef<HTMLSpanElement>(null);
    const accountAtOpen = useRef(binding.account);
    const reduced = useKitReducedMotion();
    const from = useMemo(() => {
        if (!isOpen || reduced || !spaceForBindingTravel()) return null;
        const rect = visibleBounds(trigger);
        if (!rect) return null;
        return {
            x: (rect.left + rect.width / 2 - window.innerWidth / 2) * 0.3,
            y: (rect.top + rect.height / 2 - window.innerHeight / 2) * 0.3,
        };
    }, [isOpen, reduced, trigger]);

    useLayoutEffect(() => {
        if (isOpen && trigger && titleMark.current && !reduced && spaceForBindingTravel()) {
            setArrival(titleTravel(trigger, titleMark.current, restingHeight));
        }
    }, [isOpen, trigger, reduced]);

    useEffect(() => {
        if (!isOpen || !successfulUser) return;
        const timer = window.setTimeout(() => {
            if (!reduced && spaceForBindingTravel()) {
                const source = visibleBounds(avatar.current);
                const target = bindingMark(resolveLanding());
                const destination = visibleBounds(target);
                if (source && target && destination) setDeparture({ from: source, to: destination, target });
            }
            onClose();
        }, successHoldMs);
        return () => window.clearTimeout(timer);
    }, [isOpen, onClose, reduced, resolveLanding, successfulUser]);

    const finishArrival = useCallback(() => setArrival(null), []);
    const finishDeparture = useCallback(() => setDeparture(null), []);

    function markBound(account: NcmAccountView) {
        setSuccessfulUser({
            id: String(account.userId ?? ''),
            nickname: account.nickname?.trim() || '网易云账号',
            avatarUrl: account.avatarUrl,
        });
        onBound(account);
    }

    const closeDisabled = work === 'saving' || work === 'reviewing';
    const color = platformMarkOf('netease', providerLabel).color;
    return <>
        <Sheet isOpen={isOpen} size="m" restHeight={spaceForBindingTravel() ? restingHeight : undefined}
            title={accountAtOpen.current?.bound ? '重新绑定网易云' : '绑定网易云'}
            titleLead={<span ref={titleMark} className="inline-flex"><PlatformMark providerId="netease" label={providerLabel} size={24} /></span>}
            onClose={onClose} closeDisabled={closeDisabled} closeDisabledTip="请等待当前操作完成" from={from}
            underlay={<div className="absolute inset-0" style={{
                background: 'radial-gradient(62% 120% at 100% 0%, color-mix(in srgb, ' + color + ' 17%, transparent), transparent 72%)',
            }} />}>
            <HaloBindingContent {...binding} account={accountAtOpen.current} onBound={markBound}
                work={work} setWork={setWork} color={color} successfulUser={successfulUser} avatarRef={avatar} />
        </Sheet>
        {isOpen && !reduced && arrival && <BindingFlight key="arrival" travel={arrival} onFinish={finishArrival} />}
        {!reduced && departure && <BindingFlight key="departure" travel={departure} user={successfulUser} onFinish={finishDeparture} />}
    </>;
}

function HaloBindingContent({ account, onBound, onLogin, work, setWork, color, successfulUser, avatarRef }: BindingProps & {
    work?: Work;
    setWork: (work?: Work) => void;
    color: string;
    successfulUser?: ProviderUser;
    avatarRef: RefObject<HTMLSpanElement | null>;
}) {
    const present = useIsPresent();
    const reduced = useKitReducedMotion();
    const [editing, setEditing] = useState(!account?.bound || account.readable === false);
    const [method, setMethod] = useState<Method>('qr');
    const [qr, setQr] = useState<Qr>({ phase: 'loading' });
    const [qrAttempt, setQrAttempt] = useState(0);
    const [phone, setPhone] = useState('');
    const [country, setCountry] = useState('86');
    const [resendAt, setResendAt] = useState(0);
    const [remaining, setRemaining] = useState(0);
    const [problem, setProblem] = useState<Problem>();
    const [notice, setNotice] = useState('');
    const [review, setReview] = useState(false);
    const beforeSave = useRef<NcmAccountView | undefined>(undefined);
    const operation = useRef(false);
    const lifetime = useRef<AbortController | null>(null);
    const qrStatus = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!present) return;
        const controller = new AbortController();
        lifetime.current = controller;
        return () => controller.abort();
    }, [present]);

    useEffect(() => {
        if (!present || !editing || method !== 'qr' || successfulUser) return;
        const controller = new AbortController();
        const { signal } = controller;
        let timer: number | undefined;

        function schedule(key: string, image: string) {
            timer = window.setTimeout(() => { void poll(key, image); }, 2000);
        }

        async function poll(key: string, image: string) {
            try {
                const result = await bindingApi.qrCheck(key, signal);
                if (signal.aborted) return;
                if (result.code === 801 || result.code === 802) {
                    const phase = result.code === 801 ? 'waiting' : 'scanned';
                    setQr(current => current.phase === phase || current.phase === 'scanned' ? current : { phase, key, image });
                    schedule(key, image);
                } else if (result.code === 803) {
                    setQr({ phase: 'authorized', key });
                } else if (result.code === 800) {
                    setQr({ phase: 'expired', image });
                } else {
                    setQr({ phase: 'failed', problem: { text: '暂时无法确认扫码状态，请重新获取二维码。' } });
                }
            } catch (error) {
                if (!signal.aborted) {
                    setQr({ phase: 'failed', problem: describe(error, '扫码状态读取失败，请重新获取二维码。') });
                }
            }
        }

        async function generate() {
            try {
                const [{ key, qrurl }, { default: QRCode }] = await Promise.all([bindingApi.qrKey(signal), import('qrcode')]);
                if (signal.aborted) return;
                if (!key || !qrurl) throw new Error('Incomplete QR response');
                const image = await QRCode.toDataURL(qrurl, { width: 224, margin: 4 });
                if (signal.aborted) return;
                setQr({ phase: 'waiting', key, image });
                schedule(key, image);
            } catch (error) {
                if (!signal.aborted) {
                    setQr({ phase: 'failed', problem: describe(error, '二维码加载失败，请重试。') });
                }
            }
        }

        void generate();
        return () => {
            controller.abort();
            window.clearTimeout(timer);
        };
    }, [present, editing, method, qrAttempt, successfulUser]);

    useEffect(() => {
        if (!present || !resendAt) return;
        const timer = window.setInterval(() => {
            const seconds = Math.max(0, Math.ceil((resendAt - Date.now()) / 1000));
            setRemaining(seconds);
            if (!seconds) window.clearInterval(timer);
        }, 1000);
        return () => window.clearInterval(timer);
    }, [present, resendAt]);

    function changeMethod(next: Method) {
        if (next === method || operation.current || review || successfulUser) return;
        setMethod(next);
        setQr({ phase: 'loading' });
        setProblem(undefined);
        setNotice('');
    }

    function moveMethod(event: KeyboardEvent<HTMLDivElement>) {
        const direction = event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 :
            event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : 0;
        if (!direction || work || review || successfulUser || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
        const buttons = Array.from(event.currentTarget.querySelectorAll('button'));
        const index = buttons.findIndex(button => button === event.target);
        if (index < 0) return;
        const next = (index + direction + methods.length) % methods.length;
        event.preventDefault();
        buttons[next]?.focus();
        changeMethod(methods[next].value);
    }

    function refreshQr() {
        qrStatus.current?.closest<HTMLElement>('[role="dialog"]')?.focus({ preventScroll: true });
        setProblem(undefined);
        setNotice('');
        setQr({ phase: 'loading' });
        setQrAttempt(value => value + 1);
    }

    async function run(kind: Work, action: (signal: AbortSignal) => Promise<void>) {
        const signal = lifetime.current?.signal;
        if (!signal || signal.aborted || operation.current) return;
        operation.current = true;
        setWork(kind);
        setNotice('');
        setProblem(undefined);
        try {
            await action(signal);
        } catch (error) {
            if (signal.aborted) return;
            const status = error instanceof LibraryRequestError ? error.status : undefined;
            if (kind === 'saving' && status === 410) {
                setQr({ phase: 'expired' });
            } else if (kind === 'saving' && (status === undefined || status === 0 || status === 409 || status >= 500)) {
                setReview(true);
                setProblem({ text: '暂时无法确认保存结果，请先核对绑定。' });
            } else {
                setProblem(describe(error, failures[kind]));
            }
        } finally {
            operation.current = false;
            if (!signal.aborted) setWork(undefined);
        }
    }

    function save(submit: (signal: AbortSignal) => Promise<NcmLoginResult>) {
        if (review || successfulUser) return;
        void run('saving', async signal => {
            beforeSave.current = account;
            const result = await submit(signal);
            if (signal.aborted) return;
            if (result.code !== 200) {
                setProblem({ text: result.message || '绑定失败，请检查登录信息。' });
            } else if (result.account?.bound && result.account.readable !== false) {
                onBound(result.account);
            } else {
                setReview(true);
                setProblem({ text: '暂时无法确认保存结果，请先核对绑定。' });
            }
        });
    }

    function reviewBinding() {
        void run('reviewing', async signal => {
            const current = await libraryApi.binding(signal);
            if (signal.aborted) return;
            const before = beforeSave.current;
            if (current.bound && current.readable !== false && (!before?.bound || before.readable === false ||
                current.userId !== before.userId || current.boundAt !== before.boundAt)) {
                onBound(current);
                return;
            }
            setReview(false);
            setNotice('已核对当前绑定，可以重试。');
        });
    }

    function sendCode() {
        if (!/^\d{1,4}$/.test(country.trim()) || !/^\d{5,15}$/.test(phone.trim())) {
            setProblem({ text: '请输入有效的区号和手机号。' });
            return;
        }
        void run('sending', async signal => {
            const result = await bindingApi.captcha({ phone: phone.trim(), ctcode: country.trim() }, signal);
            if (signal.aborted) return;
            if (result.code !== 200) {
                setProblem({ text: result.message || '验证码发送失败，请重试。' });
                return;
            }
            setRemaining(60);
            setResendAt(Date.now() + 60000);
            setNotice('验证码已发送。');
        });
    }

    function saveByPhone(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        const proof = String(new FormData(event.currentTarget).get('proof') ?? '');
        save(signal => bindingApi.cellphone({
            phone: phone.trim(),
            countrycode: country.trim(),
            ...(method === 'sms' ? { captcha: proof.trim() } : { password: proof }),
        }, signal));
    }

    const busy = work !== undefined;
    const error = problem ?? (method === 'qr' && qr.phase === 'failed' ? qr.problem : undefined);
    const expiredImage = qr.phase === 'expired' && qr.image;
    const phase: BindingPhase = successfulUser ? 'success' : work === 'saving' ? 'saving' :
        work === 'reviewing' ? 'reviewing' : qr.phase;
    const copy = bindingCopy[phase];
    const showScene = method === 'qr' || Boolean(successfulUser);
    const recovery = error?.signIn ? <SheetButton onClick={onLogin}>登录 Halo</SheetButton> :
        review ? <SheetButton disabled={busy} onClick={reviewBinding}>
            {work === 'reviewing' ? '正在核对…' : '核对绑定'}
        </SheetButton> : null;
    const qrAction = recovery ?? (qr.phase === 'authorized' && !successfulUser
        ? <SheetButton variant="primary" disabled={busy} onClick={() => save(signal => bindingApi.qrConfirm({ key: qr.key }, signal))}>
            {work === 'saving' ? '正在保存…' : '确认绑定'}
        </SheetButton>
        : (qr.phase === 'expired' && !expiredImage) || qr.phase === 'failed'
            ? <SheetButton onClick={refreshQr}>重新获取二维码</SheetButton>
            : null);
    const fieldLabel = 'grid min-w-0 gap-1.5 text-[13px] text-[color:var(--st-t2)]';
    return <div data-halo-binding data-login-still={reduced || undefined}
        style={{ '--login-pc': color } as CSSProperties}
        className="grid gap-4 text-[13px] max-sm:[&_button]:min-h-11">
        {!editing ? <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-[var(--st-card)] p-4">
            <p>已绑定 {account?.nickname?.trim() || '网易云账号'}</p>
            <SheetButton onClick={() => setEditing(true)}>重新绑定网易云</SheetButton>
        </div> : <>
            {account?.bound && <p className="text-[color:var(--st-t2)]">
                {account.managedInConsole ? '保存后，个人曲库会使用新绑定的账号。' : '保存后会替换当前的网易云绑定。'}
            </p>}
            <div role="group" aria-label="绑定方式" onKeyDown={moveMethod}>
                <Segmented value={method} options={methods} fill disabled={busy || review || Boolean(successfulUser)} onChange={changeMethod} />
            </div>
            <div key={method} className="login-in min-w-0" data-halo-binding-method={method}>
                {showScene ? <div data-halo-qr-phase={method === 'qr' ? qr.phase : undefined} data-halo-binding-scene={phase}
                    className="grid items-center justify-items-center gap-4 min-[540px]:grid-cols-[200px_minmax(0,1fr)] min-[540px]:justify-items-stretch min-[540px]:gap-6">
                    <BindingTile phase={phase} image={'image' in qr ? qr.image : undefined}
                        user={successfulUser} avatarRef={avatarRef} onRefresh={expiredImage ? refreshQr : undefined} />
                    <div className="grid w-full min-w-0 grid-cols-1 gap-1 text-center min-[540px]:text-start">
                        <div ref={qrStatus} role="status" aria-live="polite" aria-atomic="true"
                            className="min-h-12 min-[540px]:min-h-[4.5rem]">
                            <div key={phase} className="login-in space-y-1.5">
                                <p className="inline-flex items-center gap-1.5 text-base font-semibold">
                                    {successfulUser && <Check size={17} aria-hidden className="text-[color:var(--st-ok)]" />}
                                    {copy.title}
                                </p>
                                {successfulUser ? <p className="truncate text-[color:var(--st-t2)]"
                                    title={successfulUser.nickname}>{successfulUser.nickname}</p> :
                                    copy.detail && <p className="text-[color:var(--st-t2)]">{copy.detail}</p>}
                            </div>
                            {notice && <p className="mt-1.5 text-[color:var(--st-ok)]">{notice}</p>}
                        </div>
                        {error && <p className="text-[color:var(--st-err)] [overflow-wrap:anywhere]" role="alert">{error.text}</p>}
                        {method === 'qr' && <div className="flex min-h-11 items-center justify-center min-[540px]:justify-start">
                            {qrAction}
                        </div>}
                        {method === 'qr' && <ol className="halo-binding-steps" aria-label="绑定进度">
                            {bindingSteps.map((step, index) => <li key={step}
                                aria-current={index === copy.step ? 'step' : undefined}>{step}</li>)}
                        </ol>}
                    </div>
                </div> : <form className="grid gap-3 rounded-xl bg-[var(--st-card)] p-3 sm:p-4" onSubmit={saveByPhone}>
                    <div className="grid grid-cols-[4.5rem_minmax(0,1fr)] gap-2 sm:grid-cols-[6rem_minmax(0,1fr)]">
                        <label className={fieldLabel}>区号<input className={fieldClass} type="text" autoComplete="tel-country-code"
                            inputMode="numeric" pattern="[0-9]{1,4}" maxLength={4} required value={country}
                            onChange={event => setCountry(event.target.value)} /></label>
                        <label className={fieldLabel}>手机号<input className={fieldClass} type="tel" autoComplete="tel-national"
                            inputMode="numeric" pattern="[0-9]{5,15}" maxLength={15} required value={phone}
                            onChange={event => setPhone(event.target.value)} /></label>
                    </div>
                    {method === 'sms' ? <div className="grid grid-cols-[minmax(0,1fr)_auto] items-end gap-2">
                        <label className={fieldLabel}>短信验证码<input className={fieldClass} type="text" name="proof"
                            autoComplete="one-time-code" inputMode="numeric" pattern="[0-9]{4,8}" maxLength={8} required /></label>
                        <SheetButton disabled={busy || review || remaining > 0} onClick={sendCode}>
                            {work === 'sending' ? '正在发送…' : remaining > 0 ? remaining + ' 秒后重发' : '发送验证码'}
                        </SheetButton>
                    </div> : <label className={fieldLabel}>网易云密码<input className={fieldClass} type="password"
                        name="proof" autoComplete="current-password" maxLength={128} required /></label>}
                    {!review && <div className="flex justify-end">
                        <button type="submit" disabled={busy}
                            className="st-t-fast h-8 rounded-lg bg-[var(--st-accent)] px-3 text-[13px] text-[color:var(--st-on-accent)] transition-[opacity,scale] enabled:hover:opacity-90 enabled:active:scale-[0.97] disabled:opacity-40">
                            {work === 'saving' ? '正在保存…' : '确认绑定'}
                        </button>
                    </div>}
                </form>}
            </div>
            {!showScene && <>
                {error && <p key={error.text} className="login-in rounded-lg bg-[var(--st-fill)] px-3 py-2 text-[color:var(--st-err)]" role="alert">{error.text}</p>}
                {notice && <div className="login-in"><Status tone="ok">{notice}</Status></div>}
                {recovery && <div className="flex justify-end">{recovery}</div>}
            </>}
        </>}
    </div>;
}
