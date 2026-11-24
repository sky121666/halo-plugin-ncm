<script setup lang="ts">
import { submitForm } from '@formkit/core'
import { Toast, VAlert, VButton, VCard, VLoading } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { isAxiosError } from 'axios'
import { ncmConsoleApiClient, ncmWrite, siteAccountApi } from '@/api'
import type { NcmFrontstageConfig, NcmMusicPageMetadata } from '@/api/generated'
import { accountsRoute, featuredRoute, musicPageUrl } from '@/consoleNavigation'

const formId = 'ncm-music-page-settings'
const api = ncmConsoleApiClient.frontstageSettings
type MusicPageForm = Omit<NcmFrontstageConfig, 'settings'> & {
  settings: NcmFrontstageConfig['settings'] & { metadata: Required<NcmMusicPageMetadata> }
}
const form = ref<MusicPageForm>()
const sections = [
  { id: 'page', label: '页面与分享' },
  { id: 'search', label: '搜索' },
  { id: 'explore', label: '探索' },
  { id: 'playback', label: '播放' },
]
const section = ref('page')
const shareOptions = ref<HTMLDetailsElement>()
const loading = ref(true)
const loadFailed = ref(false)
const saving = ref(false)
const saveError = ref('')
const conflict = ref(false)
const bound = ref<boolean>()
const canManageAccounts = computed(() => utils.permission.has(['plugin:ncm:manage']))

function applySettings(data: NcmFrontstageConfig) {
  const metadata = data.settings.metadata
  form.value = {
    revision: data.revision,
    settings: {
      ...data.settings,
      metadata: {
        icon: metadata?.icon ?? '',
        iconSource: metadata?.iconSource ?? (metadata?.icon?.trim() ? 'custom' : 'site'),
        seoTitle: metadata?.seoTitle ?? '',
        shareImage: metadata?.shareImage ?? '',
      },
    },
  }
}

function showInvalidSection() {
  const metadata = form.value?.settings.metadata
  section.value = metadata?.iconSource === 'custom' && !metadata.icon ? 'page' : 'playback'
}

async function load() {
  loading.value = true
  loadFailed.value = false
  saveError.value = ''
  conflict.value = false
  try {
    applySettings((await api.getNcmFrontstageSettings()).data)
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.value || saving.value || conflict.value) return
  saving.value = true
  saveError.value = ''
  try {
    const settings = form.value.settings
    const { data } = await api.updateNcmFrontstageSettings({
      ...ncmWrite,
      ncmFrontstageConfig: {
        revision: form.value.revision,
        settings: {
          ...settings,
          metadata: { ...settings.metadata, icon: settings.metadata.icon ?? '' },
          pageSize: Number(settings.pageSize),
        },
      },
    })
    applySettings(data)
    Toast.success('已保存音乐页设置')
  } catch (error) {
    const status = isAxiosError(error) ? error.response?.status : undefined
    conflict.value = status === 409
    if (conflict.value) {
      saveError.value = '音乐页设置已在别处更新，请载入最新设置。'
    } else if (status === 400) {
      section.value = 'page'
      if (shareOptions.value) shareOptions.value.open = true
      saveError.value = '请检查图标和分享封面是否使用站内路径或 HTTP(S) 图片地址。'
    } else {
      saveError.value = '音乐页设置保存失败，请重试。'
    }
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
  if (canManageAccounts.value) {
    void siteAccountApi
      .status()
      .then((account) => (bound.value = account.bound))
      .catch(() => undefined)
  }
})
</script>

<template>
  <section class="min-w-0 max-w-3xl" aria-labelledby="ncm-music-settings-title">
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h2 id="ncm-music-settings-title" class="font-medium">音乐页设置</h2>
      <a
        :href="musicPageUrl"
        target="_blank"
        rel="noopener"
        class="text-sm text-gray-700 underline underline-offset-4"
      >
        查看音乐页
      </a>
    </div>

    <VLoading v-if="loading" />
    <VAlert v-else-if="loadFailed" type="error" title="音乐页设置读取失败" :closable="false">
      <template #actions>
        <VButton size="sm" @click="load">重试</VButton>
      </template>
    </VAlert>

    <FormKit
      v-else-if="form"
      :id="formId"
      type="form"
      :actions="false"
      :disabled="saving"
      @submit="save"
      @submit-invalid="showInvalidSection"
    >
      <div class="grid grid-cols-1 gap-4">
        <VCard class="min-w-0">
          <template #header>
            <div role="group" aria-label="音乐首页设置分区" class="flex w-full flex-wrap gap-2 p-3">
              <VButton
                v-for="item in sections"
                :key="item.id"
                size="sm"
                :type="section === item.id ? 'secondary' : 'default'"
                :aria-pressed="section === item.id"
                @click="section = item.id"
              >
                {{ item.label }}
              </VButton>
            </div>
          </template>
          <div v-show="section === 'page'">
            <FormKit
              v-model="form.settings.enabled"
              type="switch"
              name="enabled"
              label="开启音乐页"
              help="开启后访客可访问 /music。"
            />
            <FormKit
              v-model="form.settings.title"
              type="text"
              name="title"
              label="音乐页名称"
              placeholder="网抑云"
              help="显示在音乐页左上角，留空使用网抑云。"
            />
            <FormKit
              v-model="form.settings.metadata.iconSource"
              type="radio"
              name="iconSource"
              label="音乐页图标"
              fieldset-class="$reset min-w-0 border-0 p-0"
              legend-class="$reset mb-2 block text-sm font-medium text-gray-700"
              options-class="$reset flex flex-wrap items-center gap-x-5 gap-y-2"
              :options="[
                { label: '使用站点图标', value: 'site' },
                { label: '自定义', value: 'custom' },
              ]"
              :help="form.settings.metadata.iconSource === 'site'
                ? '跟随 Halo「设置 → 基本设置」中的 Favicon。'
                : '显示在浏览器标签和音乐首页名称旁。'"
            />
            <FormKit
              v-if="form.settings.metadata.iconSource === 'custom'"
              v-model="form.settings.metadata.icon"
              id="ncm-music-icon"
              type="attachment"
              name="icon"
              label="自定义图标"
              :accepts="['image/*']"
              width="60px"
              aspect-ratio="1"
              validation="required"
              :validation-messages="{ required: '请选择自定义图标' }"
              preserve
            />
            <details ref="shareOptions" class="rounded-md border border-gray-200 px-3 py-2">
              <summary class="cursor-pointer text-sm font-medium">分享与搜索摘要</summary>
              <div class="mt-3">
                <FormKit
                  v-model="form.settings.metadata.seoTitle"
                  type="text"
                  name="seoTitle"
                  label="搜索和分享标题"
                  :placeholder="form.settings.title || '音乐'"
                  help="用于首页浏览器标签、搜索结果和分享卡片。留空时使用音乐页名称。"
                />
                <FormKit
                  v-model="form.settings.description"
                  type="textarea"
                  name="description"
                  label="页面描述"
                  help="用于搜索摘要和链接分享。"
                  :rows="3"
                />
                <FormKit
                  v-model="form.settings.metadata.shareImage"
                  type="attachment"
                  name="shareImage"
                  label="首页分享封面"
                  :accepts="['image/*']"
                  width="144px"
                  aspect-ratio="1.91 / 1"
                  help="分享首页链接时使用。详情页优先使用自己的封面。"
                />
              </div>
            </details>
          </div>

          <div v-show="section === 'search'">
            <FormKit
              v-model="form.settings.searchEnabled"
              type="switch"
              name="searchEnabled"
              label="开放点唱台"
              help="访客可搜索歌曲、歌单、专辑和歌手。"
            />
            <div v-show="form.settings.searchEnabled">
              <FormKit
                v-model="form.settings.suggestEnabled"
                type="switch"
                name="suggestEnabled"
                label="输入时显示搜索联想"
                help="在点唱台输入时显示候选词，按回车可直接搜索。"
              />
            </div>
          </div>

          <div v-show="section === 'explore'">
            <div class="mb-3 flex flex-wrap items-baseline justify-between gap-2">
              <p class="text-sm text-gray-600">开启的站长内容会显示在音乐页的「探索」，访客可见。</p>
              <RouterLink
                v-if="canManageAccounts"
                :to="featuredRoute"
                class="text-sm text-gray-700 underline underline-offset-4"
              >
                管理精选歌单
              </RouterLink>
            </div>
            <VAlert v-if="bound === false" type="info" title="站长账号尚未绑定" :closable="false">
              <template v-if="canManageAccounts" #actions>
                <VButton size="sm" :route="accountsRoute">管理站长账号</VButton>
              </template>
            </VAlert>
            <FormKit
              v-model="form.settings.sitePlaylists"
              type="switch"
              name="sitePlaylists"
              label="站长的公开歌单"
              help="读取网易云账号下公开的歌单。精选歌单在独立列表发布。"
            />
            <FormKit
              v-model="form.settings.siteRank"
              type="switch"
              name="siteRank"
              label="站长的公开听歌排行"
              help="显示网易云允许游客查看的本周排行。"
            />
            <h3 class="mt-3 border-t border-gray-200 pt-4 text-sm font-medium">
              主题接入
            </h3>
            <FormKit
              v-model="form.settings.siteProfile"
              type="switch"
              name="siteProfile"
              label="开放站长资料"
              help="供主题或前端适配读取网易云昵称、头像等公开资料。"
            />
          </div>

          <div v-show="section === 'playback'">
            <FormKit
              v-model="form.settings.resume"
              type="switch"
              name="resume"
              label="跨页自动续播"
              help="在同一标签页切换博客和音乐页时，接续正在播放的曲目。整页跳转需要浏览器允许自动播放。"
            />
            <FormKit
              v-model="form.settings.pageSize"
              type="number"
              name="pageSize"
              label="每批曲目数量"
              help="用于歌单首批加载和个人曲库分页。曲库每页最多 100 首。"
              :min="10"
              :max="200"
              validation="required|min:10|max:200"
            />
          </div>

        </VCard>

        <VAlert v-if="saveError" type="error" :title="saveError" :closable="false">
          <template v-if="conflict" #actions>
            <VButton size="sm" @click="load">载入最新设置</VButton>
          </template>
        </VAlert>
        <div
          role="group"
          aria-label="音乐页设置操作"
          class="flex flex-wrap justify-end gap-2 border-t border-gray-200 pt-3"
        >
          <VButton
            type="secondary"
            :loading="saving"
            :disabled="conflict"
            @click="submitForm(formId)"
          >
            保存音乐页设置
          </VButton>
        </div>
      </div>
    </FormKit>
  </section>
</template>
