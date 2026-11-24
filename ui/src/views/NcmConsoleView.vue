<script setup lang="ts">
import { VButton, VCard, VPageHeader } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, defineAsyncComponent, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RiMusic2Line from '~icons/ri/music-2-line'
import {
  accountsTabId,
  apiDebugTabId,
  featuredTabId,
  maintenanceTabId,
  musicPageTabId,
  toolSettingsTabId,
  pluginDisplayName,
  pluginEntryTabId,
  pluginSettingsRoute,
} from '@/consoleNavigation'
import FeaturedPanel from './FeaturedPanel.vue'

const MusicPageSettings = defineAsyncComponent(() => import('./MusicPageSettings.vue'))
const NcmAccountsView = defineAsyncComponent(() => import('./NcmAccountsView.vue'))
const NcmToolSettingsView = defineAsyncComponent(() => import('./NcmToolSettingsView.vue'))
const route = useRoute()
const router = useRouter()
const canViewPlugin = computed(() => utils.permission.has(['system:plugins:view']))
const canManageFeatured = computed(() => utils.permission.has(['plugin:ncm:manage']))
const canManageAccounts = computed(() => utils.permission.has(['plugin:ncm:account']))
const canEditSettings = computed(() => utils.permission.has(['system:plugins:manage']))
const canDebug = computed(() => utils.permission.has(['plugin:ncm:debug']))
const tabs = computed(() => [
  ...(canEditSettings.value ? [{ id: musicPageTabId, label: '音乐首页' }] : []),
  ...(canManageFeatured.value ? [{ id: featuredTabId, label: '精选歌单' }] : []),
  ...(canManageAccounts.value ? [{ id: accountsTabId, label: '账号与游客' }] : []),
  ...(canEditSettings.value ? [{ id: toolSettingsTabId, label: '工具设置' }] : []),
])
const activeTab = computed(
  () => tabs.value.find((tab) => tab.id === route.query.tab)?.id ?? tabs.value[0]?.id,
)
watch(
  [() => route.query.tab, canViewPlugin, canEditSettings, canDebug],
  ([tab, canView, canEdit, debug]) => {
    if (route.name !== 'NcmConsole' || !canView || tab !== 'maintenance') return
    if (route.query.view === 'api' && debug) {
      void router.replace(pluginSettingsRoute(apiDebugTabId))
    } else if (canEdit) {
      void router.replace(pluginSettingsRoute(maintenanceTabId))
    }
  },
  { immediate: true },
)

function selectTab(tab: string | number) {
  if (tab === activeTab.value) return
  void router.push({
    query: { ...route.query, tab, view: undefined, section: undefined, playlist: undefined },
  })
}
</script>

<template>
  <VPageHeader :title="pluginDisplayName">
    <template #icon>
      <RiMusic2Line />
    </template>
    <template #actions>
      <VButton v-if="canViewPlugin" :route="pluginSettingsRoute(pluginEntryTabId)">
        插件详情
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard :body-class="['!p-0', '!overflow-visible']">
      <template #header>
        <nav aria-label="网抑云管理分区" class="flex w-full flex-wrap items-center gap-2 p-3">
          <VButton
            v-for="tab in tabs"
            :key="tab.id"
            size="sm"
            :type="activeTab === tab.id ? 'secondary' : 'default'"
            :aria-current="activeTab === tab.id ? 'page' : undefined"
            @click="selectTab(tab.id)"
          >
            {{ tab.label }}
          </VButton>
        </nav>
      </template>
      <div class="p-4">
        <MusicPageSettings v-if="activeTab === musicPageTabId" />
        <NcmAccountsView v-else-if="activeTab === accountsTabId" />
        <NcmToolSettingsView v-else-if="activeTab === toolSettingsTabId" />
        <FeaturedPanel v-else-if="activeTab === featuredTabId" />
      </div>
    </VCard>
  </div>
</template>
