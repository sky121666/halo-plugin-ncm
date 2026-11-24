<script setup lang="ts">
import { submitForm } from '@formkit/core'
import { Toast, VAlert, VButton, VCard, VLoading, VStatusDot } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { isAxiosError } from 'axios'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ncmConsoleApiClient, ncmWrite } from '@/api'
import type { NcmToolConfig, NcmToolSettings } from '@/api/generated'
import {
  featuredRoute,
  musicPageSettingsRoute,
  musicPageUrl,
  toolSettingsSections,
  type ToolSettingsSection,
} from '@/consoleNavigation'
import MiniPlayerPositionSettings from './MiniPlayerPositionSettings.vue'

const playerFormId = 'ncm-mini-player-settings'
const aiFormId = 'ncm-music-ai-settings'
const api = ncmConsoleApiClient.toolSettings
const route = useRoute()
const router = useRouter()
const activeSection = computed<ToolSettingsSection>(
  () =>
    typeof route.query.playlist === 'string'
      ? 'mini-player'
      : (toolSettingsSections.find(({ id }) => id === route.query.section)?.id ?? 'mini-player'),
)
const openedSections = ref(new Set<ToolSettingsSection>())
watch(activeSection, (section) => {
  openedSections.value.add(section)
}, { immediate: true })

function selectSection(section: ToolSettingsSection) {
  if (section !== activeSection.value) {
    void router.push({ query: { ...route.query, section, playlist: undefined } })
  }
}

const saved = ref<NcmToolConfig>()
const draft = ref<NcmToolSettings>()
const musicPageEnabled = ref<boolean>()
const preset = ref<{ label: string; value: string }>()
const loading = ref(true)
const failed = ref(false)
const saving = ref<'miniPlayer' | 'ai'>()
const conflict = ref(false)
const saveError = ref<{ section: 'miniPlayer' | 'ai'; text: string }>()
const canManageFeatured = computed(() => utils.permission.has(['plugin:ncm:manage']))
const playlists = computed(() =>
  (saved.value?.playlists ?? []).map(({ id, title }) => ({ label: title, value: id })),
)
const presetTitle = computed(() =>
  draft.value?.miniPlayer.playlistId === preset.value?.value ? preset.value?.label : undefined,
)
const playerChanged = computed(() => {
  const next = draft.value?.miniPlayer
  const current = saved.value?.settings.miniPlayer
  return next?.enabled !== current?.enabled || next?.playlistId !== current?.playlistId
})
const needsPlaylist = computed(() => {
  const player = draft.value?.miniPlayer
  return !!player?.enabled && !playlists.value.some(({ value }) => value === player.playlistId)
})
const playerBlocked = computed(() =>
  playerChanged.value &&
  !!draft.value?.miniPlayer.enabled &&
  (needsPlaylist.value || musicPageEnabled.value === false),
)
const savedPlayerStatus = computed<{ state: 'success' | 'warning' | 'default'; text: string }>(() => {
  const player = saved.value?.settings.miniPlayer
  if (!player?.enabled) return { state: 'default', text: '已关闭' }
  if (musicPageEnabled.value === false) return { state: 'warning', text: '已暂停 · 音乐页关闭' }
  if (!playlists.value.some(({ value }) => value === player.playlistId)) {
    return { state: 'warning', text: '已暂停 · 歌单不可用' }
  }
  if (musicPageEnabled.value === undefined) return { state: 'warning', text: '音乐页状态待确认' }
  return { state: 'success', text: '已启用' }
})

async function load() {
  loading.value = true
  failed.value = false
  conflict.value = false
  saveError.value = undefined
  preset.value = undefined
  try {
    const [{ data }, musicPage] = await Promise.all([
      api.getNcmToolSettings(),
      ncmConsoleApiClient.frontstageSettings.getNcmFrontstageSettings().catch(() => undefined),
    ])
    saved.value = data
    draft.value = {
      ai: { ...data.settings.ai, allowedRoles: [...data.settings.ai.allowedRoles] },
      miniPlayer: { ...data.settings.miniPlayer },
    }
    musicPageEnabled.value = musicPage?.data.settings.enabled
    const requested = route.query.playlist
    if (typeof requested === 'string') {
      const choice = playlists.value.find(({ value }) => value === requested)
      if (choice) {
        draft.value.miniPlayer.playlistId = choice.value
        preset.value = choice
      }
      void router.replace({ query: { ...route.query, section: 'mini-player', playlist: undefined } })
    }
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function save(section: 'miniPlayer' | 'ai') {
  if (!saved.value || !draft.value || saving.value || conflict.value) return
  if (section === 'miniPlayer' && playerBlocked.value) return

  saving.value = section
  saveError.value = undefined
  try {
    const { data } = await api.updateNcmToolSettings({
      ...ncmWrite,
      ncmToolConfig: {
        ...saved.value,
        settings: { ...saved.value.settings, [section]: draft.value[section] },
      },
    })
    saved.value = data
    if (section === 'miniPlayer') {
      draft.value.miniPlayer = { ...data.settings.miniPlayer }
      preset.value = undefined
      Toast.success('已保存站点小播放器')
    } else {
      draft.value.ai = { ...data.settings.ai, allowedRoles: [...data.settings.ai.allowedRoles] }
      Toast.success('已保存音乐 AI')
    }
  } catch (error) {
    const status = isAxiosError(error) ? error.response?.status : undefined
    conflict.value = status === 409
    if (!conflict.value) {
      const fallback = section === 'miniPlayer' ? '小播放器保存失败，请重试。' : '音乐 AI 保存失败，请重试。'
      const invalid = section === 'miniPlayer'
        ? '请检查音乐页是否开启，以及所选歌单是否仍可公开访问。'
        : '请检查音乐 AI 的使用范围。'
      saveError.value = { section, text: status === 400 ? invalid : fallback }
    }
  } finally {
    saving.value = undefined
  }
}

onMounted(load)
</script>

<template>
  <div class="max-w-3xl space-y-4">
    <nav aria-label="工具设置分区" class="flex flex-wrap gap-2">
      <VButton
        v-for="section in toolSettingsSections"
        :key="section.id"
        size="sm"
        :type="section.id === activeSection ? 'secondary' : 'default'"
        :aria-pressed="section.id === activeSection"
        @click="selectSection(section.id)"
      >
        {{ section.label }}
      </VButton>
    </nav>
    <VLoading v-if="loading" />
    <VAlert
      v-else-if="failed"
      type="error"
      title="工具设置读取失败"
      :closable="false"
    >
      <template #actions><VButton size="sm" @click="load">重试</VButton></template>
    </VAlert>
    <template v-else-if="draft">
      <VAlert
        v-if="conflict"
        type="warning"
        title="工具设置已在别处更新"
        description="载入最新设置后再继续编辑。"
        :closable="false"
      >
        <template #actions><VButton size="sm" @click="load">载入最新设置</VButton></template>
      </VAlert>

      <VCard
        v-if="openedSections.has('mini-player')"
        v-show="activeSection === 'mini-player'"
        class="min-w-0"
      >
        <template #header>
          <div class="flex w-full flex-wrap items-center justify-between gap-2 px-4 py-3">
            <h2 class="font-medium">站点小播放器</h2>
            <VStatusDot :state="savedPlayerStatus.state" :text="savedPlayerStatus.text" />
          </div>
        </template>

        <FormKit :id="playerFormId" type="form" :actions="false" :disabled="!!saving" @submit="save('miniPlayer')">
          <VAlert
            v-if="musicPageEnabled === false"
            type="info"
            title="请先开启音乐页"
            description="音乐页开启后，主题页面才能显示小播放器。"
            :closable="false"
            class="mb-3"
          >
            <template #actions><VButton size="sm" :route="musicPageSettingsRoute">设置音乐页</VButton></template>
          </VAlert>
          <VAlert
            v-else-if="!playlists.length"
            type="info"
            title="暂无可用的精选歌单"
            description="发布一张访客可访问的歌单后，即可用作小播放器歌单。"
            :closable="false"
            class="mb-3"
          >
            <template v-if="canManageFeatured" #actions>
              <VButton size="sm" :route="featuredRoute">管理精选歌单</VButton>
            </template>
          </VAlert>
          <p v-if="presetTitle && playerChanged" class="mb-3 text-sm text-gray-600" role="status">
            已带入「{{ presetTitle }}」。{{ draft.miniPlayer.enabled ? '保存后切换播放歌单。' : '开启小播放器并保存后生效。' }}
          </p>
          <FormKit
            v-model="draft.miniPlayer.enabled"
            type="switch"
            name="miniPlayerEnabled"
            label="在主题页面显示小播放器"
            :disabled="!draft.miniPlayer.enabled && (musicPageEnabled === false || !playlists.length)"
          />
          <FormKit
            v-if="playlists.length && (draft.miniPlayer.enabled || preset)"
            v-model="draft.miniPlayer.playlistId"
            type="select"
            name="miniPlayerPlaylist"
            label="播放歌单"
            :options="playlists"
            placeholder="选择已发布的精选歌单"
            searchable
            preserve
          />
          <p v-if="playlists.length && needsPlaylist" class="mb-3 text-sm text-gray-600" role="status">
            请选择一张可公开访问的歌单。
          </p>
          <VAlert
            v-if="saveError?.section === 'miniPlayer'"
            type="error"
            :title="saveError.text"
            :closable="false"
            class="mb-3"
          />
          <div class="flex flex-wrap items-center justify-end gap-3 border-t border-gray-200 pt-3">
            <a
              v-if="savedPlayerStatus.state === 'success'"
              :href="'/?ncm-player-revision=' + saved?.revision"
              target="_blank"
              rel="noopener"
              class="text-sm text-gray-700 underline underline-offset-4"
            >查看站点</a>
            <VButton
              type="secondary"
              :loading="saving === 'miniPlayer'"
              :disabled="!!saving || conflict || playerBlocked"
              @click="submitForm(playerFormId)"
            >保存小播放器</VButton>
          </div>
        </FormKit>
        <MiniPlayerPositionSettings class="mt-4 border-t border-gray-200 pt-4" />
      </VCard>

      <VCard
        v-if="openedSections.has('music-ai')"
        v-show="activeSection === 'music-ai'"
        class="min-w-0"
      >
        <template #header><h2 class="w-full px-4 py-3 font-medium">音乐 AI</h2></template>
        <FormKit :id="aiFormId" type="form" :actions="false" :disabled="!!saving" @submit="save('ai')">
          <FormKit
            v-model="draft.ai.enabled"
            type="switch"
            name="aiEnabled"
            label="启用音乐 AI"
            help="需启用 AI Foundation 并配置默认语言模型。符合下方使用范围的 Halo 登录账号可以使用。"
          />
          <section
            class="mb-4 rounded-md border border-gray-200 bg-gray-50 px-3 py-2.5 text-sm"
            aria-labelledby="ncm-music-ai-feature-title"
          >
            <div class="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
              <h3 id="ncm-music-ai-feature-title" class="font-medium">音乐页功能与入口</h3>
              <a
                v-if="musicPageEnabled"
                :href="musicPageUrl"
                target="_blank"
                rel="noopener"
                class="text-sm text-gray-700 underline underline-offset-4"
              >打开音乐页</a>
            </div>
            <dl class="mt-1 divide-y divide-gray-200">
              <div class="space-y-1 py-2">
                <dt class="font-medium">歌词分词</dt>
                <dd class="text-gray-700">
                  自动分词在「设置 → 歌词 → 分词 → 自动 AI 分词」开启，适用于按词排版的中日文歌词。
                </dd>
                <dd class="text-gray-700">
                  单曲分词可在播放时按 <kbd class="font-medium">Ctrl / ⌘ + K</kbd> 打开快捷操作，搜索「歌词分词调整」后点「AI 分词」。
                </dd>
              </div>
              <div class="space-y-1 pt-2">
                <dt class="font-medium">歌曲配色</dt>
                <dd class="text-gray-700">
                  在「设置 → 外观 → 歌曲主题」将「主题生成来源」设为「AI 推断」，也可在这里开启自动生成。
                </dd>
                <dd class="text-gray-700">
                  在「播放页 → 右侧控制面板 → 控制」点「生成AI主题」，为当前歌曲生成深浅两套配色。
                </dd>
              </div>
            </dl>
          </section>
          <FormKit
            v-if="draft.ai.enabled"
            v-model="draft.ai.audience"
            type="toggle"
            name="aiAudience"
            label="使用范围"
            render-type="text"
            preserve
            :options="[
              { label: '所有已登录账号', render: '所有已登录账号', value: 'signed-in' },
              { label: '仅指定角色', render: '仅指定角色', value: 'roles' },
            ]"
          />
          <FormKit
            v-if="draft.ai.enabled && draft.ai.audience === 'roles'"
            v-model="draft.ai.allowedRoles"
            type="roleSelect"
            name="aiAllowedRoles"
            label="允许使用的角色"
            multiple
            searchable
            preserve
            :excluded-names="[
              'anonymous',
              'authenticated',
              'role-template-ncm-public',
              'role-template-ncm-personal',
            ]"
            placeholder="搜索或选择 Halo 角色"
            help="拥有任一所选角色的已登录用户可以使用。"
          />
          <VAlert v-if="saveError?.section === 'ai'" type="error" :title="saveError.text" :closable="false" class="mb-3" />
          <div class="flex justify-end border-t border-gray-200 pt-3">
            <VButton
              type="secondary"
              :loading="saving === 'ai'"
              :disabled="!!saving || conflict"
              @click="submitForm(aiFormId)"
            >保存音乐 AI</VButton>
          </div>
        </FormKit>
      </VCard>
    </template>
  </div>
</template>
