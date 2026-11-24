<script setup lang="ts">
import { utils } from '@halo-dev/ui-shared'
import { computed } from 'vue'
import DebugPanel from './DebugPanel.vue'

const canDebug = computed(() => utils.permission.has(['plugin:ncm:debug']))
</script>

<template>
  <section v-if="canDebug" class="p-4" aria-label="接口调试">
    <h2 class="font-medium">接口调试</h2>
    <p class="mb-4 mt-1 text-sm text-gray-600">使用游客身份调用公开只读接口。</p>
    <DebugPanel />
  </section>
</template>
