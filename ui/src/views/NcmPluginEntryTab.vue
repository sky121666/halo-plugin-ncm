<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import {
  accountsRoute,
  apiDebugTabId,
  featuredRoute,
  maintenanceTabId,
  musicPageSettingsRoute,
  ncmToolsRoute,
  networkSettingsTabId,
  pluginSettingsRoute,
  toolSettingsRoute,
} from '@/consoleNavigation'

const groups = computed(() => [
  {
    id: 'tools',
    title: '音乐页与账号',
    links: [
      {
        label: '精选歌单',
        description: '添加歌单、保存草稿和发布。',
        route: featuredRoute,
        permission: 'plugin:ncm:manage',
      },
      {
        label: '音乐首页',
        description: '设置标题、图标、搜索、探索、播放和 SEO。',
        route: musicPageSettingsRoute,
        permission: 'system:plugins:manage',
      },
      {
        label: '账号与游客',
        description: '管理站长账号，查看游客会话。',
        route: accountsRoute,
        permission: 'plugin:ncm:manage',
      },
      {
        label: '工具设置',
        description: '设置小播放器和音乐 AI。',
        route: toolSettingsRoute,
        permission: 'system:plugins:manage',
      },
    ],
  },
  {
    id: 'operations',
    title: '接口与维护',
    links: [
      {
        label: '维护',
        description: '查看运行状态，初始化游客或重置设备。',
        route: pluginSettingsRoute(maintenanceTabId),
        permission: 'system:plugins:manage',
      },
      {
        label: '接口调试',
        description: '查看接口能力，以游客身份调试公开只读接口。',
        route: pluginSettingsRoute(apiDebugTabId),
        permission: 'plugin:ncm:debug',
      },
      {
        label: '网络与限流',
        description: '配置代理、请求额度和缓存。',
        route: pluginSettingsRoute(networkSettingsTabId),
        permission: 'system:plugins:manage',
      },
    ],
  },
])

const availableGroups = computed(() =>
  groups.value
    .map((group) => ({
      ...group,
      links: group.links.filter(({ permission }) => utils.permission.has([permission])),
    }))
    .filter((group) => group.links.length),
)
const canOpenTools = computed(() => availableGroups.value.some((group) => group.id === 'tools'))
</script>

<template>
  <section class="p-4" aria-labelledby="ncm-plugin-entry-title">
    <div class="max-w-3xl">
      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 id="ncm-plugin-entry-title" class="font-medium">管理入口</h2>
        <VButton v-if="canOpenTools" :route="ncmToolsRoute">打开工具页</VButton>
      </div>

      <div class="grid grid-cols-1 gap-4">
        <VCard
          v-for="group in availableGroups"
          :key="group.id"
          class="min-w-0"
          role="region"
          :aria-labelledby="`ncm-plugin-entry-${group.id}`"
        >
          <template #header>
            <h3
              :id="`ncm-plugin-entry-${group.id}`"
              class="flex min-h-12 items-center px-4 py-2 font-medium"
            >
              {{ group.title }}
            </h3>
          </template>
          <ul class="divide-y divide-gray-100">
            <li v-for="link in group.links" :key="link.label">
              <RouterLink
                :to="link.route"
                class="group block rounded px-2 py-2.5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-600"
              >
                <span class="block text-sm font-medium group-hover:underline">{{
                  link.label
                }}</span>
                <span class="mt-1 block text-sm text-gray-600">{{ link.description }}</span>
              </RouterLink>
            </li>
          </ul>
        </VCard>
      </div>
    </div>
  </section>
</template>
