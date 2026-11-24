import { AnimatePresence, motion } from 'framer-motion';
import { useState } from 'react';
import { Button, Group, Row, Toggle } from '../components/modal/settings/kit';
import { setStatusMessage } from '../stores/useStatusMessageStore';
import { useHaloConfig } from './HaloConfigProvider';
import type { HaloConfigSync } from './haloConfigSync';

const group = { id: 'haloConfigSync', titleKey: 'Halo 配置同步' };
const statusText = {
    loading: '正在读取配置…',
    local: '设置保存在当前浏览器。',
    saved: '已同步。',
    pending: '已保存在本机，等待同步。',
    saving: '正在同步…',
    offline: '离线。本机修改将在联网后同步。',
    error: '操作暂不可用。本机修改已保留。',
    conflict: '云端已有更新。本机修改已保留。',
    clearing: '正在清除个人云端配置…',
    cleared: '已清除个人云端配置。',
};
const siteStatusText = {
    loading: '正在读取站点默认…',
    ready: '用于游客和未保存个人配置的用户。',
    saving: '正在保存…',
    saved: '已发布，刷新后生效。',
    restored: '已恢复上一版站点默认，刷新后生效。',
    conflict: '站点默认已更新。请先重新读取。',
    error: '操作暂不可用，请重新读取。',
};

async function publishSiteDefaults(service: HaloConfigSync) {
    if (await service.publish()) setStatusMessage({ type: 'success', text: '已保存为站点默认' });
}

export function HaloSiteDefaultsFooter({ reducedMotion }: { reducedMotion: boolean }) {
    const { service, state } = useHaloConfig();
    const busy = state.siteStatus === 'loading' || state.siteStatus === 'saving';
    const needsRead = state.siteStatus === 'conflict' || state.siteStatus === 'error';
    const message = busy || needsRead ? siteStatusText[state.siteStatus] : '设置已修改';

    return <AnimatePresence initial={false}>
        {service && state.viewer?.canPublishSite && state.siteDirty && <motion.footer
            key="halo-site-defaults"
            data-halo-site-defaults-footer=""
            className="flex shrink-0 flex-wrap items-center justify-between gap-x-4 gap-y-2 border-t border-[var(--st-line2)] bg-[var(--st-card)] px-4 py-2.5 md:col-span-2 md:px-8"
            initial={reducedMotion ? false : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={reducedMotion ? undefined : { opacity: 0, y: 8 }}
            transition={{ duration: reducedMotion ? 0 : 0.16, ease: 'easeOut' }}
        >
            <span role="status" className="min-w-0 text-[13px] text-[color:var(--st-t1)]">{message}</span>
            <div className="ml-auto [&_button]:min-h-11 md:[&_button]:min-h-0">
                <Button variant="primary" disabled={busy} onClick={() => {
                    if (needsRead) void service.loadSite(false);
                    else void publishSiteDefaults(service);
                }}>{needsRead ? '重新读取' : '保存为站点默认'}</Button>
            </div>
        </motion.footer>}
    </AnimatePresence>;
}

export function HaloConfigPanel() {
    const { service, state } = useHaloConfig();
    const [confirm, setConfirm] = useState<{ action: 'clear' | 'restore'; profileKey: string }>();
    if (!service) return null;
    const activeConfirmation = confirm?.profileKey === state.viewer?.profileKey ? confirm?.action : undefined;
    const writing = state.status === 'saving' || state.status === 'clearing';
    const hasCloudConfig = Object.keys(state.viewer?.snapshot.config ?? {}).length > 0;
    const personalStatus = state.status === 'saved' && !hasCloudConfig
        ? '同步已开启，正在使用默认配置。'
        : statusText[state.status];
    const siteBusy = state.siteStatus === 'loading' || state.siteStatus === 'saving';
    const needsSiteRead = state.siteStatus === 'conflict' || state.siteStatus === 'error';

    return <Group def={group}>
        <Row label="个人配置"
            hint={state.auth === 'guest' ? '登录 Halo 后可跨设备同步设置。'
                : state.enabled ? '调节后自动同步到 Halo。' : '启用后同步外观、播放、歌词等设置。'}>
            {state.viewer && <Toggle checked={state.enabled} disabled={state.status === 'clearing'}
                onChange={enabled => service.setEnabled(enabled)} />}
            {!state.viewer && state.auth === 'loading' && <span role="status">正在读取…</span>}
        </Row>
        {(state.enabled || state.auth === 'error' || state.status === 'cleared' || state.status === 'clearing'
            || state.status === 'error') && <Row label={<span role="status">{personalStatus}</span>}>
            {state.status === 'conflict' && <div className="flex flex-wrap gap-2">
                <Button onClick={() => void service.resolveConflict(false)}>使用云端</Button>
                <Button onClick={() => void service.resolveConflict(true)}>保留本机</Button>
            </div>}
            {(state.status === 'error' || state.status === 'offline') && <Button
                onClick={() => void service.checkAccount('account')}>重新读取</Button>}
        </Row>}
        {state.viewer && hasCloudConfig && <Row label="清除个人云端配置"
            hint={activeConfirmation === 'clear' ? '将清除这个账号已同步的设置和本机待同步修改。' : '已开启同步的设备会重新跟随站点默认。'}>
            {activeConfirmation === 'clear' ? <div className="flex flex-wrap gap-2">
                <Button disabled={writing} onClick={() => { setConfirm(undefined); void service.clearPersonal(); }}>确认清除</Button>
                <Button onClick={() => setConfirm(undefined)}>取消</Button>
            </div> : <Button disabled={writing}
                onClick={() => setConfirm({ action: 'clear', profileKey: state.viewer!.profileKey })}>清除</Button>}
        </Row>}
        {state.viewer?.canPublishSite && <Row label="站点默认"
            hint={<span role="status">{siteStatusText[state.siteDirty ? 'ready' : state.siteStatus]}</span>}>
            {!state.siteDirty && (needsSiteRead ? <Button onClick={() => void service.loadSite(false)}>重新读取</Button>
                : activeConfirmation !== 'restore' && <Button disabled={siteBusy}
                    onClick={() => void publishSiteDefaults(service)}>保存为站点默认</Button>)}
        </Row>}
        {state.viewer?.canPublishSite && state.siteHasPrevious && !needsSiteRead
            && <Row label="恢复上一版站点默认"
                hint={activeConfirmation === 'restore' ? '将上一版设为新的站点默认。' : '可撤回最近一次站点默认变更。'}>
                {activeConfirmation === 'restore' ? <div className="flex flex-wrap gap-2">
                    <Button disabled={siteBusy}
                        onClick={() => { setConfirm(undefined); void service.restoreSite(); }}>确认恢复</Button>
                    <Button onClick={() => setConfirm(undefined)}>取消</Button>
                </div> : <Button disabled={siteBusy}
                    onClick={() => setConfirm({ action: 'restore', profileKey: state.viewer!.profileKey })}>恢复</Button>}
            </Row>}
    </Group>;
}
