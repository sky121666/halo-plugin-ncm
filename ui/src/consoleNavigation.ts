export const accountsTabId = 'account'
export const featuredTabId = 'featured'
export const musicPageTabId = 'music-page'
export const pluginEntryTabId = 'ncm-entry'
export const legacySiteAccountTabId = 'ncm-site-account'
export const maintenanceTabId = 'ncm-maintenance'
export const networkSettingsTabId = 'advanced'
// 沿用旧的访客与接口链接。
export const apiManagementTabId = 'basic'
export const apiRequestOptionsId = 'ncm-api-request-options'
const pluginName = 'plugin-ncm'
export const siteAccountConsoleUrl = `/console/ncm?tab=${accountsTabId}`
export const accountsRoute = { name: 'NcmConsole', query: { tab: accountsTabId } }
export const ncmToolsRoute = { name: 'NcmConsole' }
export const featuredRoute = { name: 'NcmConsole', query: { tab: featuredTabId } }
export const musicPageSettingsRoute = { name: 'NcmConsole', query: { tab: musicPageTabId } }
export const musicPageUrl = '/music'

export function pluginSettingsRoute(tab: string, view?: 'debug') {
  return { name: 'PluginDetail', params: { name: pluginName }, query: { tab, view } }
}
