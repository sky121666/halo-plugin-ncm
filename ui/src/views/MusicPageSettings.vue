<script setup lang="ts">
import { submitForm } from '@formkit/core'
import { Toast, VAlert, VButton, VCard, VLoading } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { isAxiosError } from 'axios'
import { ncmConsoleApiClient, ncmWrite, siteAccountApi } from '@/api'
import type { NcmFrontstageConfig } from '@/api/generated'
import { accountsRoute, featuredRoute, musicPageUrl } from '@/consoleNavigation'

const formId = 'ncm-music-page-settings'
const api = ncmConsoleApiClient.frontstageSettings
const form = ref<NcmFrontstageConfig>()
const loading = ref(true)
const loadFailed = ref(false)
const saving = ref(false)
const saveError = ref('')
const conflict = ref(false)
const bound = ref<boolean>()
const canManageAccounts = computed(() => utils.permission.has(['plugin:ncm:manage']))

async function load() {
  loading.value = true
  loadFailed.value = false
  saveError.value = ''
  conflict.value = false
  try {
    form.value = (await api.getNcmFrontstageSettings()).data
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
          pageSize: Number(settings.pageSize),
        },
      },
    })
    form.value = data
    Toast.success('已保存音乐页设置')
  } catch (error) {
    conflict.value = isAxiosError(error) && error.response?.status === 409
    saveError.value = conflict.value
      ? '音乐页设置已在别处更新，本次未保存。'
      : '音乐页设置保存失败，请重试。'
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
      <nav aria-label="音乐页设置相关入口" class="flex flex-wrap gap-x-4 gap-y-2 text-sm">
        <RouterLink
          v-if="canManageAccounts"
          :to="featuredRoute"
          class="text-gray-700 underline underline-offset-4"
        >
          精选歌单
        </RouterLink>
        <a
          :href="musicPageUrl"
          target="_blank"
          rel="noopener"
          class="text-gray-700 underline underline-offset-4"
        >
          查看音乐页
        </a>
      </nav>
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
    >
      <div class="grid grid-cols-1 gap-4">
        <VCard role="group" aria-labelledby="ncm-music-page-search" class="min-w-0">
          <template #header>
            <h3 id="ncm-music-page-search" class="flex min-h-12 items-center px-4 py-2 font-medium">
              页面与搜索
            </h3>
          </template>
          <FormKit
            v-model="form.settings.enabled"
            type="switch"
            name="enabled"
            label="开启音乐页"
            help="开启后访客可访问 /music。"
          />
          <FormKit v-model="form.settings.title" type="text" name="title" label="页面标题" />
          <FormKit
            v-model="form.settings.description"
            type="textarea"
            name="description"
            label="页面简介"
            :rows="3"
          />
          <FormKit
            v-model="form.settings.searchEnabled"
            type="switch"
            name="searchEnabled"
            label="开启搜索页"
          />
          <div v-show="form.settings.searchEnabled">
            <FormKit
              v-model="form.settings.suggestEnabled"
              type="switch"
              name="suggestEnabled"
              label="显示搜索联想"
            />
          </div>
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
        </VCard>

        <VCard role="group" aria-labelledby="ncm-music-site-content" class="min-w-0">
          <template #header>
            <h3 id="ncm-music-site-content" class="flex min-h-12 items-center px-4 py-2 font-medium">
              站长公开内容
            </h3>
          </template>
          <p class="mb-3 text-sm text-gray-600">开启的内容会公开给访客，主题也可读取。</p>
          <VAlert v-if="bound === false" type="info" title="站长账号尚未绑定" :closable="false">
            <template v-if="canManageAccounts" #actions>
              <VButton size="sm" :route="accountsRoute">管理站长账号</VButton>
            </template>
          </VAlert>
          <FormKit
            v-model="form.settings.siteProfile"
            type="switch"
            name="siteProfile"
            label="站长资料"
          />
          <FormKit
            v-model="form.settings.sitePlaylists"
            type="switch"
            name="sitePlaylists"
            label="网易云公开歌单"
          />
          <FormKit
            v-model="form.settings.siteRecent"
            type="switch"
            name="siteRecent"
            label="最近播放"
          />
          <FormKit
            v-model="form.settings.siteRank"
            type="switch"
            name="siteRank"
            label="听歌排行"
          />
          <FormKit
            v-model="form.settings.siteRecommend"
            type="switch"
            name="siteRecommend"
            label="每日推荐"
          />
        </VCard>

        <VCard role="group" aria-labelledby="ncm-music-playback" class="min-w-0">
          <template #header>
            <h3 id="ncm-music-playback" class="flex min-h-12 items-center px-4 py-2 font-medium">
              播放行为
            </h3>
          </template>
          <FormKit
            v-model="form.settings.resume"
            type="switch"
            name="resume"
            label="回到音乐页时自动续播"
            help="续播需要浏览器允许自动播放。"
          />
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
