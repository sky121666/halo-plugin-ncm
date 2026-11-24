<script setup lang="ts">
import { utils } from '@halo-dev/ui-shared'
import { computed, defineAsyncComponent, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  accountsRoute,
  apiDebugTabId,
  maintenanceTabId,
  networkSettingsTabId,
  pluginSettingsRoute,
} from '@/consoleNavigation'

const DevicePanel = defineAsyncComponent(() => import('./DevicePanel.vue'))
const route = useRoute()
const router = useRouter()
const canMaintain = computed(() => utils.permission.has(['system:plugins:manage']))
const canDebug = computed(() => utils.permission.has(['plugin:ncm:debug']))
const canManageAccounts = computed(() => utils.permission.has(['plugin:ncm:manage']))
const legacyLink = computed(
  () =>
    route.name === 'PluginDetail' &&
    route.query.tab === maintenanceTabId &&
    route.query.view === 'api',
)

watch(
  legacyLink,
  (active) => {
    if (!active) return
    const target = pluginSettingsRoute(canDebug.value ? apiDebugTabId : networkSettingsTabId)
    if (canMaintain.value) {
      void router.replace(target)
    } else {
      // Halo 在进入插件详情时读取页签，重新进入以移除旧链接使用的临时入口。
      window.location.replace(router.resolve(target).href)
    }
  },
  { immediate: true },
)
</script>

<template>
  <section v-if="!legacyLink && canMaintain" class="p-4" aria-label="维护">
    <div class="mb-4 flex max-w-3xl flex-wrap items-center justify-between gap-3">
      <h2 class="font-medium">维护</h2>
      <nav aria-label="维护相关入口" class="flex flex-wrap gap-x-4 gap-y-2 text-sm">
        <RouterLink
          v-if="canManageAccounts"
          :to="accountsRoute"
          class="text-gray-700 underline underline-offset-4"
        >
          账号与游客
        </RouterLink>
        <RouterLink
          :to="pluginSettingsRoute(networkSettingsTabId)"
          class="text-gray-700 underline underline-offset-4"
        >
          网络与限流
        </RouterLink>
      </nav>
    </div>
    <DevicePanel />
  </section>
</template>
