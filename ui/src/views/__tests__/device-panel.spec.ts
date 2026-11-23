import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NcmDeviceStatus } from '@/api/generated'
import DevicePanel from '../DevicePanel.vue'

const { api, dialog, toast } = vi.hoisted(() => ({
  api: {
    getNcmDeviceStatus: vi.fn(),
    refreshNcmDevice: vi.fn(),
    resetNcmDevice: vi.fn(),
  },
  dialog: vi.fn<(options: { onConfirm: () => Promise<void> }) => void>(),
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))

vi.mock('@/api', () => ({ ncmConsoleApiClient: { device: api }, ncmWrite: { xNCMRequest: '1' } }))
vi.mock('@halo-dev/ui-shared', () => ({ utils: { date: { format: (value?: string) => value } } }))
vi.mock('@halo-dev/components', () => ({
  Dialog: { warning: dialog },
  Toast: toast,
  VButton: {
    props: { disabled: Boolean, loading: Boolean, type: String },
    template: '<button :disabled="disabled"><slot /></button>',
  },
  VCard: { template: '<section><slot name="header" /><slot /></section>' },
  VAlert: { props: ['title', 'description'], template: '<div>{{ title }}{{ description }}</div>' },
  VDescription: { template: '<div><slot /></div>' },
  VDescriptionItem: {
    props: ['label', 'content'],
    template: '<div>{{ label }}{{ content }}<slot /></div>',
  },
  VLoading: { template: '<span />' },
  VStatusDot: { props: ['text'], template: '<span>{{ text }}</span>' },
}))

const ready: NcmDeviceStatus = {
  anonymousReady: true,
  credentialSecrets: '',
  deviceId: 'fixture-device',
  lastAttempt: '1970-01-01T00:00:00Z',
  lastError: '',
  nmtidReady: true,
  proxy: '',
  secretKeyPersistent: true,
  secretKeySource: 'file',
  storeError: '',
  xeapiReady: true,
  xeapiSession: false,
}

describe('设备维护操作', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.getNcmDeviceStatus.mockResolvedValue({ data: ready })
  })

  it('刷新失败时保留展开的详情和上次状态', async () => {
    let rejectRead!: (error: Error) => void
    const pending = new Promise<{ data: NcmDeviceStatus }>((_resolve, reject) => {
      rejectRead = reject
    })
    api.getNcmDeviceStatus.mockResolvedValueOnce({ data: ready }).mockReturnValueOnce(pending)
    const panel = mount(DevicePanel)
    await flushPromises()

    const details = panel.get('details').element as HTMLDetailsElement
    details.open = true
    expect(panel.text()).toContain('最近初始化尝试暂无')
    const refresh = panel.get('[aria-labelledby="ncm-device-status-title"] button')
    await refresh.trigger('click')
    await refresh.trigger('click')
    expect(api.getNcmDeviceStatus).toHaveBeenCalledTimes(2)
    expect(panel.get('details').element).toBe(details)
    expect(details.open).toBe(true)

    rejectRead(new Error('network failure'))
    await flushPromises()
    expect(panel.text()).toContain('当前显示上次读取结果。')
    expect(panel.text()).toContain('已注册')
    expect(details.open).toBe(true)
    expect(api.refreshNcmDevice).not.toHaveBeenCalled()
    expect(api.resetNcmDevice).not.toHaveBeenCalled()
    panel.unmount()
  })

  it('重置在确认后执行，保存失败时提示未完成', async () => {
    api.resetNcmDevice.mockResolvedValue({ data: { ...ready, storeError: '写入失败' } })
    const panel = mount(DevicePanel)
    await flushPromises()

    const reset = panel.findAll('button').find((button) => button.text() === '重置设备身份')!
    await reset.trigger('click')
    expect(api.resetNcmDevice).not.toHaveBeenCalled()
    await dialog.mock.calls[0]![0].onConfirm()

    expect(api.resetNcmDevice).toHaveBeenCalledWith({ xNCMRequest: '1' })
    expect(toast.warning).toHaveBeenCalledWith('设备重置未完成，请查看运行状态')
    expect(toast.success).not.toHaveBeenCalled()
    panel.unmount()
  })
})
