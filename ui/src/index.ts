import { definePlugin, utils, type PluginTab } from '@halo-dev/ui-shared'
import { defineAsyncComponent, markRaw } from 'vue'
import RiMusic2Line from '~icons/ri/music-2-line'
import {
  apiManagementTabId,
  legacySiteAccountTabId,
  maintenanceTabId,
  musicAiSettingsTabId,
  pluginDisplayName,
  pluginEntryTabId,
} from '@/consoleNavigation'

const legacySiteAccountTab = markRaw(
  defineAsyncComponent(() => import('./views/NcmSiteAccountTab.vue')),
)
const maintenanceTab = markRaw(defineAsyncComponent(() => import('./views/NcmMaintenanceTab.vue')))
const pluginEntryTab = markRaw(defineAsyncComponent(() => import('./views/NcmPluginEntryTab.vue')))
const legacyAiSettingsTab = markRaw(
  defineAsyncComponent(() => import('./views/NcmLegacyAiSettingsTab.vue')),
)
const apiManagementTab = markRaw(
  defineAsyncComponent(() => import('./views/NcmApiManagementTab.vue')),
)

export default definePlugin({
  components: {},
  // 工具页集中管理音乐页、精选歌单与账号。
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/ncm',
        name: 'NcmConsole',
        component: () => import('./views/NcmConsoleView.vue'),
        meta: {
          title: pluginDisplayName,
          searchable: true,
          permissions: ['plugin:ncm:manage', 'system:plugins:manage'],
          menu: {
            name: pluginDisplayName,
            group: 'tool',
            icon: markRaw(RiMusic2Line),
            priority: 0,
          },
        },
      },
    },
  ],
  // 个人中心：注册用户绑定自己的网易云账号
  ucRoutes: [
    {
      parentName: 'Root',
      route: {
        path: '/ncm',
        name: 'NcmUcBinding',
        component: () => import('./views/NcmUcBindingView.vue'),
        meta: {
          title: pluginDisplayName,
          searchable: true,
          menu: {
            name: pluginDisplayName,
            icon: markRaw(RiMusic2Line),
            priority: 10,
          },
        },
      },
    },
  ],
  extensionPoints: {
    'plugin:self:tabs:create': (): PluginTab[] => {
      const query = new URLSearchParams(window.location.search)
      const legacyDebugRedirect =
        query.get('tab') === maintenanceTabId &&
        query.get('view') === 'api' &&
        !utils.permission.has(['system:plugins:manage'])
      const tabs: PluginTab[] = [
        {
          id: pluginEntryTabId,
          label: '管理入口',
          component: pluginEntryTab,
          permissions: ['plugin:ncm:manage', 'system:plugins:manage', 'plugin:ncm:debug'],
        },
        {
          id: maintenanceTabId,
          label: legacyDebugRedirect ? '接口调试' : '维护',
          component: maintenanceTab,
          permissions: [legacyDebugRedirect ? 'plugin:ncm:debug' : 'system:plugins:manage'],
        },
        {
          id: apiManagementTabId,
          label: '接口管理',
          component: apiManagementTab,
          permissions: ['system:plugins:manage', 'plugin:ncm:debug'],
        },
      ]
      if (query.get('tab') === legacySiteAccountTabId) {
        tabs.push({
          id: legacySiteAccountTabId,
          label: '站长账号',
          component: legacySiteAccountTab,
          permissions: ['plugin:ncm:manage'],
        })
      }
      if (query.get('tab') === musicAiSettingsTabId) {
        tabs.push({
          id: musicAiSettingsTabId,
          label: '音乐 AI',
          component: legacyAiSettingsTab,
          permissions: ['system:plugins:manage'],
        })
      }
      return tabs
    },
    // 默认编辑器的音乐卡片。按需加载，不进入后台首屏。
    'default:editor:extension:create': async () => {
      const { ExtensionNcmCard } = await import('./editor')
      return [ExtensionNcmCard]
    },
  },
})
