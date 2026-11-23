import { OnlineProviderError, type MediaId, type ProviderUser } from '../types/onlineMusic';
import type { NcmAccountView } from './generated/ncm-account-view';

let account: NcmAccountView | undefined;
let session = new AbortController();

export function foliaBindingKey(binding?: NcmAccountView) {
    return binding?.bound && binding.readable !== false && binding.userId
        ? JSON.stringify([binding.userId, binding.boundAt, Boolean(binding.managedInConsole)]) : '';
}

export function setFoliaBinding(binding?: NcmAccountView) {
    const previous = foliaBindingKey(account);
    const next = foliaBindingKey(binding);
    account = next ? binding : undefined;
    if (previous === next) return false;
    session.abort();
    session = new AbortController();
    return true;
}

export function foliaUser(): ProviderUser | null {
    return account?.userId ? {
        id: String(account.userId),
        nickname: account.nickname?.trim() || '网易云账号',
        avatarUrl: account.avatarUrl,
        vipType: account.vipType,
    } : null;
}

export const foliaSessionSignal = () => session.signal;

export function requireFoliaBinding(userId?: MediaId) {
    const user = foliaUser();
    if (!user || (userId !== undefined && String(userId) !== user.id)) {
        throw new OnlineProviderError('auth-required', '请先绑定网易云账号。', 'netease');
    }
    return session.signal;
}
