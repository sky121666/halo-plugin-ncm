import { PanelRow } from '../components/panelTab/shell/PanelRow';
import { HaloAccountAction, useHaloAccount } from './HaloAccount';

export function HaloPlayerAccount() {
    const { state } = useHaloAccount();
    const label = state.kind === 'guest' ? '游客模式' :
        state.kind === 'account' && state.account.managedInConsole ? '站长账号' : '网易云账号';

    return <PanelRow label={label}>
        <HaloAccountAction variant="panel" />
    </PanelRow>;
}
