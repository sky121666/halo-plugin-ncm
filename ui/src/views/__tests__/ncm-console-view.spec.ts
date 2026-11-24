import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { VButton, VTabbar } from '@halo-dev/components'
import { RouterLink } from 'vue-router'
import { maintenanceTabId, pluginEntryTabId } from '@/consoleNavigation'
import plugin from '@/index'
import NcmConsoleView from '../NcmConsoleView.vue'
import NcmPluginEntryTab from '../NcmPluginEntryTab.vue'

const { permissions } = vi.hoisted(() => ({ permissions: new Set<string>() }))

vi.mock('@halo-dev/ui-shared', () => ({
  definePlugin: (definition: unknown) => definition,
  utils: {
    permission: { has: (required: string[]) => required.some((id) => permissions.has(id)) },
  },
}))

vi.mock('@halo-dev/components', () => ({
  VTabbar: { props: ['activeId', 'items'], template: '<div />' },
  VCard: { template: '<div><slot name="header" /><slot /></div>' },
  VPageHeader: { template: '<div><slot name="actions" /></div>' },
  VButton: { props: ['route'], template: '<button><slot /></button>' },
}))

vi.mock('vue-router', () => ({
  RouterLink: { props: ['to'], template: '<a><slot /></a>' },
  useRoute: () => ({ name: 'NcmConsole', query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

vi.mock('../MusicPageSettings.vue', () => ({
  __esModule: true,
  default: { template: '<div data-panel="settings" />' },
}))

vi.mock('../FeaturedPanel.vue', () => ({
  default: { template: '<div data-panel="featured" />' },
}))

describe('网易云音乐工具入口', () => {
  beforeEach(() => permissions.clear())

  it('允许任一管理权限进入工具页', () => {
    const entry = plugin.routes?.[0]
    const route = entry && 'route' in entry ? entry.route : entry
    expect(route?.meta?.permissions).toEqual(['plugin:ncm:manage', 'system:plugins:manage'])
  })

  it('仅有调试权限时旧调试链接使用临时调试入口', async () => {
    permissions.add('plugin:ncm:debug')
    const url = window.location.href
    const createTabs = plugin.extensionPoints?.['plugin:self:tabs:create']
    const findMaintenance = async () => (await createTabs?.())?.find((t) => t.id === maintenanceTabId)

    try {
      window.history.replaceState(null, '', `?tab=${maintenanceTabId}&view=api`)
      expect(await findMaintenance()).toMatchObject({
        label: '接口调试',
        permissions: ['plugin:ncm:debug'],
      })

      window.history.replaceState(null, '', '?tab=basic&view=debug')
      expect(await findMaintenance()).toMatchObject({
        label: '维护',
        permissions: ['system:plugins:manage'],
      })
    } finally {
      window.history.replaceState(null, '', url)
    }
  })

  it('插件管理员从工具页进入插件详情的管理入口', async () => {
    permissions.add('system:plugins:view')
    permissions.add('system:plugins:manage')
    const view = mount(NcmConsoleView)
    await vi.dynamicImportSettled()
    await flushPromises()

    expect(view.getComponent(VTabbar).props('items')).toEqual([
      { id: 'music-page', label: '音乐页设置' },
    ])
    expect(view.find('[data-panel="settings"]').exists()).toBe(true)
    expect(view.find('[data-panel="featured"]').exists()).toBe(false)
    expect(view.getComponent(VButton).text()).toBe('插件详情')
    expect(view.getComponent(VButton).props('route')).toMatchObject({
      name: 'PluginDetail',
      query: { tab: pluginEntryTabId },
    })
    view.unmount()
  })

  it('只有网易云音乐管理权限时保留精选歌单', () => {
    permissions.add('plugin:ncm:manage')
    const view = mount(NcmConsoleView)

    expect(view.getComponent(VTabbar).props('items')).toEqual([
      { id: 'featured', label: '精选歌单' },
      { id: 'account', label: '账号与游客' },
    ])
    expect(view.find('[data-panel="featured"]').exists()).toBe(true)
    expect(view.find('[data-panel="settings"]').exists()).toBe(false)
    expect(view.findComponent(VButton).exists()).toBe(false)
    view.unmount()
  })

  it('内容管理员拥有插件查看权限时也能打开管理入口', () => {
    permissions.add('plugin:ncm:manage')
    permissions.add('system:plugins:view')
    const view = mount(NcmConsoleView)

    expect(view.getComponent(VButton).text()).toBe('插件详情')
    expect(view.getComponent(VButton).props('route')).toMatchObject({
      name: 'PluginDetail',
      query: { tab: pluginEntryTabId },
    })
    view.unmount()
  })

  it.each([
    {
      permission: 'plugin:ncm:manage',
      destinations: ['featured', 'account'],
      tools: true,
    },
    {
      permission: 'system:plugins:manage',
      destinations: ['music-page', 'ncm-maintenance', 'basic', 'advanced', 'ai'],
      tools: true,
    },
    {
      permission: 'plugin:ncm:debug',
      destinations: ['basic:debug'],
      tools: false,
    },
  ])('管理入口按 $permission 显示对应链接', ({ permission, destinations, tools }) => {
    permissions.add(permission)
    const view = mount(NcmPluginEntryTab)
    const links = view.findAllComponents(RouterLink).map((link) => {
      const to = link.props('to') as { query: { tab: string; view?: string } }
      return [to.query.tab, to.query.view].filter(Boolean).join(':')
    })

    expect(links).toEqual(destinations)
    expect(view.findComponent(VButton).exists()).toBe(tools)
    view.unmount()
  })
})
