<script setup lang="ts">
import { submitForm } from '@formkit/core'
import { Toast, VAlert, VButton, VLoading } from '@halo-dev/components'
import { isAxiosError } from 'axios'
import { onMounted, ref, watchPostEffect } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ncmConsoleApiClient, ncmWrite } from '@/api'
import type { NcmApiAccessConfig } from '@/api/generated'
import {
  apiRequestOptionsId,
  networkSettingsTabId,
  pluginSettingsRoute,
} from '@/consoleNavigation'

const formId = 'ncm-api-access-settings'
const title = '接口开放'
const api = ncmConsoleApiClient.apiAccessSettings
const route = useRoute()
const requestOptions = ref<HTMLDetailsElement>()
const form = ref<NcmApiAccessConfig>()
const loading = ref(true)
const failed = ref(false)
const saving = ref(false)
const conflict = ref(false)
const saveError = ref('')

async function load() {
  loading.value = true
  failed.value = false
  conflict.value = false
  saveError.value = ''
  try {
    form.value = (await api.getNcmApiAccessSettings()).data
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.value || saving.value || conflict.value) return
  saving.value = true
  saveError.value = ''
  try {
    const { revision, settings } = form.value
    const result = await api.updateNcmApiAccessSettings({
      ...ncmWrite,
      ncmApiAccessConfig: {
        revision,
        settings: { ...settings, cacheSeconds: Number(settings.cacheSeconds) },
      },
    })
    form.value = result.data
    Toast.success(`已保存${title}`)
  } catch (error) {
    conflict.value = isAxiosError(error) && error.response?.status === 409
    saveError.value = conflict.value
      ? `${title}已在别处更新，请载入最新设置。`
      : `${title}保存失败，请重试。`
  } finally {
    saving.value = false
  }
}

onMounted(load)

watchPostEffect(() => {
  if (route.hash === `#${apiRequestOptionsId}` && requestOptions.value) {
    requestOptions.value.open = true
    requestOptions.value.querySelector('summary')?.focus()
  }
})
</script>

<template>
  <section class="max-w-xl" :aria-label="title">
    <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
      <h2 class="font-medium">{{ title }}</h2>
      <nav

        aria-label="接口相关设置"
        class="flex flex-wrap gap-x-3 gap-y-1 text-sm"
      >
        <RouterLink
          :to="pluginSettingsRoute(networkSettingsTabId)"
          class="text-gray-700 underline underline-offset-4"
        >
          网络与限流
        </RouterLink>
      </nav>
    </div>
    <p class="mb-4 mt-1 text-sm text-gray-600">
      公开读取使用游客身份。个人读取与写入使用当前 Halo 用户的网易云绑定。
    </p>
    <VLoading v-if="loading" />
    <VAlert v-else-if="failed" type="error" :title="`${title}读取失败`" :closable="false">
      <template #actions><VButton size="sm" @click="load">重试</VButton></template>
    </VAlert>
    <FormKit
      v-else-if="form"
      :id="formId"
      type="form"
      :actions="false"
      :disabled="saving"
      @submit="save"
    >
      <fieldset

        class="mb-4 min-w-0 rounded-md border border-gray-200 px-3 pb-3 pt-2"
      >
        <legend class="px-1 text-sm font-medium">通用接口</legend>
        <FormKit
          v-model="form.settings.apiScope"
          type="toggle"
          name="apiScope"
          label="开放范围"
          render-type="text"
          :options="[
            { label: '常用只读', render: '常用只读', value: 'common' },
            { label: '全部通用接口', render: '全部通用接口', value: 'all' },
          ]"
        />
        <VAlert
          v-if="form.settings.apiScope === 'all'"
          type="warning"
          title="此范围包含写入接口"
          description="已获授权的调用方可以发起写入操作。"
          :closable="false"
        />
        <FormKit
          v-show="form.settings.apiScope !== 'all'"
          v-model="form.settings.extraApis"
          type="textarea"
          name="extraApis"
          label="额外开放接口"
          help="在常用只读范围上追加接口。填写接口名，用逗号或换行分隔。"
        />
      </fieldset>

      <details

        :id="apiRequestOptionsId"
        ref="requestOptions"
        class="mb-4 rounded-md border border-gray-200 px-3 py-2"
      >
        <summary class="cursor-pointer text-sm font-medium">接口缓存与请求兼容</summary>
        <FormKit
          v-model="form.settings.cacheSeconds"
          outer-class="mt-4"
          type="number"
          name="cacheSeconds"
          label="通用公开接口缓存（秒）"
          :min="0"
          validation="required|min:0"
          help="适用于可缓存的公开读取请求。0 表示关闭。"
        />
        <FormKit
          v-model="form.settings.realIp"
          type="text"
          name="realIp"
          label="发往网易云的固定 IP 请求头"
          help="配置发往网易云的 X-Real-IP 和 X-Forwarded-For 值。"
        />
        <FormKit
          v-model="form.settings.randomCnIp"
          type="switch"
          name="randomCnIp"
          label="IP 请求头使用随机中国大陆地址"
          help="仅在固定 IP 请求头留空时生效。"
        />
      </details>
      <VAlert v-if="saveError" type="error" :title="saveError" :closable="false">
        <template v-if="conflict" #actions>
          <VButton size="sm" @click="load">载入最新设置</VButton>
        </template>
      </VAlert>
      <VButton type="secondary" :loading="saving" :disabled="conflict" @click="submitForm(formId)">
        保存接口设置
      </VButton>
    </FormKit>
  </section>
</template>
