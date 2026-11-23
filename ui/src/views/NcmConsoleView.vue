<script setup lang="ts">
import { VButton, VCard, VPageHeader, VTabbar } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, defineAsyncComponent, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RiNeteaseCloudMusicLine from '~icons/ri/netease-cloud-music-line'
import {
  accountsTabId,
  apiManagementTabId,
  featuredTabId,
  maintenanceTabId,
  musicPageTabId,
  pluginEntryTabId,
  pluginSettingsRoute,
} from '@/consoleNavigation'
import FeaturedPanel from './FeaturedPanel.vue'

const MusicPageSettings = defineAsyncComponent(() => import('./MusicPageSettings.vue'))
const NcmAccountsView = defineAsyncComponent(() => import('./NcmAccountsView.vue'))
const route = useRoute()
const router = useRouter()
const canViewPlugin = computed(() => utils.permission.has(['system:plugins:view']))
const canManageFeatured = computed(() => utils.permission.has(['plugin:ncm:manage']))
const canEditSettings = computed(() => utils.permission.has(['system:plugins:manage']))
const canDebug = computed(() => utils.permission.has(['plugin:ncm:debug']))
const tabs = computed(() => [
  ...(canManageFeatured.value ? [{ id: featuredTabId, label: '精选歌单' }] : []),
  ...(canEditSettings.value ? [{ id: musicPageTabId, label: '音乐页设置' }] : []),
  ...(canManageFeatured.value ? [{ id: accountsTabId, label: '账号与游客' }] : []),
])
const activeTab = computed(
  () => tabs.value.find((tab) => tab.id === route.query.tab)?.id ?? tabs.value[0]?.id,
)
watch(
  [() => route.query.tab, canViewPlugin, canEditSettings, canDebug],
  ([tab, canView, canEdit, debug]) => {
    if (route.name !== 'NcmConsole' || !canView || tab !== 'maintenance') return
    if (route.query.view === 'api' && debug) {
      void router.replace(pluginSettingsRoute(apiManagementTabId, 'debug'))
    } else if (canEdit) {
      void router.replace(pluginSettingsRoute(maintenanceTabId))
    }
  },
  { immediate: true },
)

function selectTab(tab: string | number) {
  if (tab === activeTab.value) return
  void router.push({
    query: { ...route.query, tab: tab === featuredTabId ? undefined : tab, view: undefined },
  })
}
</script>

<template>
  <VPageHeader title="网易云音乐工具">
    <template #icon>
      <RiNeteaseCloudMusicLine />
    </template>
    <template #actions>
      <VButton
        v-if="canViewPlugin"
        :route="pluginSettingsRoute(pluginEntryTabId)"
      >
        插件详情
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard :body-class="['!p-0', '!overflow-visible']">
      <template #header>
        <VTabbar
          :active-id="activeTab"
          :items="tabs"
          class="w-full !rounded-none"
          type="outline"
          @update:active-id="selectTab"
        />
      </template>
      <div class="p-4">
        <MusicPageSettings v-if="activeTab === musicPageTabId" />
        <NcmAccountsView v-else-if="activeTab === accountsTabId" />
        <FeaturedPanel v-else-if="activeTab === featuredTabId" />
      </div>
    </VCard>
  </div>
</template>
