import { definePlugin, utils, type PluginTab } from '@halo-dev/ui-shared'
import { defineAsyncComponent, markRaw } from 'vue'
import RiNeteaseCloudMusicLine from '~icons/ri/netease-cloud-music-line'
import {
  apiManagementTabId,
  legacySiteAccountTabId,
  maintenanceTabId,
  pluginEntryTabId,
} from '@/consoleNavigation'

const legacySiteAccountTab = markRaw(
  defineAsyncComponent(() => import('./views/NcmSiteAccountTab.vue')),
)
const maintenanceTab = markRaw(defineAsyncComponent(() => import('./views/NcmMaintenanceTab.vue')))
const pluginEntryTab = markRaw(defineAsyncComponent(() => import('./views/NcmPluginEntryTab.vue')))
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
          title: '网易云音乐工具',
          searchable: true,
          permissions: ['plugin:ncm:manage', 'system:plugins:manage'],
          menu: {
            name: '网易云音乐',
            group: 'tool',
            icon: markRaw(RiNeteaseCloudMusicLine),
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
          title: '网易云音乐',
          searchable: true,
          menu: {
            name: '网易云音乐',
            icon: markRaw(RiNeteaseCloudMusicLine),
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
      return tabs
    },
    // 默认编辑器：工具箱和斜杠菜单里的“网易云音乐卡片”。按需加载，不进入后台首屏
    'default:editor:extension:create': async () => {
      const { ExtensionNcmCard } = await import('./editor')
      return [ExtensionNcmCard]
    },
  },
})
