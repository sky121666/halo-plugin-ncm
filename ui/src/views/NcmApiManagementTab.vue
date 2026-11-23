<script setup lang="ts">
import { VButton } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, defineAsyncComponent, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiManagementTabId, pluginSettingsRoute } from '@/consoleNavigation'

const ApiAccessSettings = defineAsyncComponent(() => import('./ApiAccessSettings.vue'))
const DebugPanel = defineAsyncComponent(() => import('./DebugPanel.vue'))
const route = useRoute()
const router = useRouter()
const panel = ref<'policy' | 'debug'>('policy')
const canConfigure = computed(() => utils.permission.has(['system:plugins:manage']))
const canDebug = computed(() => utils.permission.has(['plugin:ncm:debug']))
const showDebug = computed(
  () =>
    canDebug.value &&
    (!canConfigure.value ||
      (route.name === 'PluginDetail' ? route.query.view === 'debug' : panel.value === 'debug')),
)

function select(view: 'policy' | 'debug') {
  if (route.name === 'PluginDetail') {
    void router.push(
      pluginSettingsRoute(apiManagementTabId, view === 'debug' ? 'debug' : undefined),
    )
  } else {
    panel.value = view
  }
}
</script>

<template>
  <section class="p-4" aria-label="接口管理">
    <div
      v-if="canConfigure && canDebug"
      class="mb-4 flex flex-wrap gap-2"
      role="group"
      aria-label="接口管理视图"
    >
      <VButton
        size="sm"
        :type="showDebug ? 'default' : 'secondary'"
        :aria-pressed="!showDebug"
        @click="select('policy')"
      >
        接口开放
      </VButton>
      <VButton
        size="sm"
        :type="showDebug ? 'secondary' : 'default'"
        :aria-pressed="showDebug"
        @click="select('debug')"
      >
        接口调试
      </VButton>
    </div>
    <template v-if="showDebug">
      <h2 class="font-medium">接口调试</h2>
      <p class="mb-4 mt-1 text-sm text-gray-600">使用游客身份调用常用只读接口。</p>
      <DebugPanel />
    </template>
    <ApiAccessSettings v-else-if="canConfigure" />
  </section>
</template>
