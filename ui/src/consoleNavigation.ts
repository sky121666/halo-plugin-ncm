export const accountsTabId = 'account'
export const featuredTabId = 'featured'
export const musicPageTabId = 'music-page'
export const toolSettingsTabId = 'tool-settings'
export const pluginEntryTabId = 'ncm-entry'
export const legacySiteAccountTabId = 'ncm-site-account'
export const maintenanceTabId = 'ncm-maintenance'
export const networkSettingsTabId = 'advanced'
export const musicAiSettingsTabId = 'ai'
// 沿用旧的访客与接口链接。
export const apiDebugTabId = 'basic'
export const pluginDisplayName = '网抑云'
const pluginName = 'plugin-ncm'
export const siteAccountConsoleUrl = `/console/ncm?tab=${accountsTabId}`
export const accountsRoute = { name: 'NcmConsole', query: { tab: accountsTabId } }
export const ncmToolsRoute = { name: 'NcmConsole' }
export const featuredRoute = { name: 'NcmConsole', query: { tab: featuredTabId } }
export const musicPageSettingsRoute = { name: 'NcmConsole', query: { tab: musicPageTabId } }
export const toolSettingsRoute = { name: 'NcmConsole', query: { tab: toolSettingsTabId } }
export const musicPageUrl = '/music'

export const toolSettingsSections = [
  { id: 'mini-player', label: '站点小播放器' },
  { id: 'music-ai', label: '音乐 AI' },
] as const
export type ToolSettingsSection = (typeof toolSettingsSections)[number]['id']

export function toolSettingsSectionRoute(section: ToolSettingsSection) {
  return { ...toolSettingsRoute, query: { ...toolSettingsRoute.query, section } }
}

export function miniPlayerSettingsRoute(playlistId: string) {
  const route = toolSettingsSectionRoute('mini-player')
  return { ...route, query: { ...route.query, playlist: playlistId } }
}

export function pluginSettingsRoute(tab: string) {
  return { name: 'PluginDetail', params: { name: pluginName }, query: { tab } }
}
