<script setup lang="ts">
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VCard,
  VDescription,
  VDescriptionItem,
  VLoading,
  VStatusDot,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, onMounted, ref } from 'vue'
import { ncmConsoleApiClient, ncmWrite } from '@/api'
import type { NcmDeviceStatus } from '@/api/generated'
import { formatGuestAttempt, guestStatusLabels } from '@/guestStatus'

const deviceApi = ncmConsoleApiClient.device
const status = ref<NcmDeviceStatus>()
const operation = ref<'read' | 'initialize' | 'reset'>()
const loadFailed = ref(false)

const guestState = computed(() => {
  if (status.value?.anonymousReady) return 'registered'
  return status.value?.lastError || status.value?.storeError ? 'failed' : 'pending'
})

const actions = {
  initialize: {
    request: () => deviceApi.refreshNcmDevice(ncmWrite),
    success: '游客初始化完成',
    incomplete: '游客初始化未完成，请查看运行状态',
    failure: '游客初始化请求失败，请刷新状态后核对',
  },
  reset: {
    request: () => deviceApi.resetNcmDevice(ncmWrite),
    success: '设备身份已重置，游客已注册',
    incomplete: '设备重置未完成，请查看运行状态',
    failure: '设备重置请求失败，请刷新状态后核对',
  },
}

async function load() {
  if (operation.value) return
  operation.value = 'read'
  loadFailed.value = false
  try {
    status.value = (await deviceApi.getNcmDeviceStatus()).data
  } catch {
    loadFailed.value = true
  } finally {
    operation.value = undefined
  }
}

async function runAction(action: keyof typeof actions) {
  if (operation.value) return
  operation.value = action
  const feedback = actions[action]
  try {
    const { data } = await feedback.request()
    status.value = data
    loadFailed.value = false
    if (data.xeapiReady && data.anonymousReady && !data.lastError && !data.storeError) {
      Toast.success(feedback.success)
    } else {
      Toast.warning(feedback.incomplete)
    }
  } catch {
    Toast.error(feedback.failure)
  } finally {
    operation.value = undefined
  }
}

function reset() {
  if (operation.value) return
  Dialog.warning({
    title: '重置设备身份',
    description: '将替换当前设备 ID，重新获取网易云公钥并注册游客。',
    confirmType: 'danger',
    confirmText: '重置设备身份',
    onConfirm: () => runAction('reset'),
  })
}

const fmt = (v?: string | null) => utils.date.format(v) || '-'

const KEY_SOURCE: Record<string, string> = {
  env: '环境变量 NCM_SECRET_KEY',
  file: '密钥文件',
  memory: '内存',
}

onMounted(load)
</script>

<template>
  <div class="ncm-device grid max-w-3xl grid-cols-1 gap-4">
    <VCard
      class="min-w-0"
      role="region"
      aria-labelledby="ncm-device-status-title"
      :aria-busy="operation === 'read'"
    >
      <template #header>
        <div class="flex min-h-12 w-full flex-wrap items-center justify-between gap-2 px-4 py-2">
          <h3 id="ncm-device-status-title" class="font-medium">运行状态</h3>
          <VButton size="sm" :loading="operation === 'read'" :disabled="!!operation" @click="load">
            刷新状态
          </VButton>
        </div>
      </template>

      <VAlert
        v-if="loadFailed"
        type="error"
        title="运行状态读取失败"
        :description="status ? '当前显示上次读取结果。' : undefined"
        :closable="false"
        class="mb-3"
      />
      <VLoading v-if="!status && !loadFailed" />
      <template v-if="status">
        <VAlert
          v-if="!status.secretKeyPersistent"
          type="error"
          title="凭据加密密钥仅在内存中"
          description="Halo 重启后需重新绑定网易云账号。可配置 NCM_SECRET_KEY 或检查 Halo 工作目录写入权限。"
          :closable="false"
          class="mb-3"
        />
        <VAlert
          v-if="status.lastError || status.storeError"
          type="warning"
          title="运行异常"
          :closable="false"
          class="mb-3"
        >
          <template #description>
            <div v-if="status.lastError">{{ status.lastError }}</div>
            <div v-if="status.storeError">{{ status.storeError }}</div>
          </template>
        </VAlert>

        <VDescription>
          <VDescriptionItem label="游客会话">
            <VStatusDot v-bind="guestStatusLabels[guestState]" />
          </VDescriptionItem>
          <VDescriptionItem
            label="最近初始化尝试"
            :content="formatGuestAttempt(status.lastAttempt)"
          />
          <VDescriptionItem label="网易云公钥">
            <VStatusDot
              :state="status.xeapiReady ? 'success' : 'warning'"
              :text="status.xeapiReady ? '已就绪' : '未获取'"
            />
          </VDescriptionItem>
          <VDescriptionItem label="凭据加密">
            <VStatusDot
              :state="status.secretKeyPersistent ? 'success' : 'error'"
              :text="status.secretKeyPersistent ? '密钥已持久化' : '密钥仅在内存'"
            />
          </VDescriptionItem>
          <VDescriptionItem label="出站代理" :content="status.proxy || '直连'" />
        </VDescription>
      </template>
    </VCard>

    <VCard v-if="status" class="min-w-0" role="region" aria-labelledby="ncm-device-actions-title">
      <template #header>
        <h3 id="ncm-device-actions-title" class="flex min-h-12 items-center px-4 py-2 font-medium">
          维护操作
        </h3>
      </template>
      <div class="flex flex-wrap items-center justify-between gap-3 pb-3">
        <p class="min-w-0 flex-1 basis-52 text-sm text-gray-600">重新获取网易云公钥并注册游客。</p>
        <VButton
          :disabled="!!operation"
          :loading="operation === 'initialize'"
          @click="runAction('initialize')"
        >
          重新初始化游客
        </VButton>
      </div>
      <div class="flex flex-wrap items-center justify-between gap-3 border-t border-gray-100 pt-3">
        <p class="min-w-0 flex-1 basis-52 text-sm text-gray-600">
          重新生成设备 ID。多次初始化失败时使用。
        </p>
        <VButton type="danger" :disabled="!!operation" :loading="operation === 'reset'" @click="reset">
          重置设备身份
        </VButton>
      </div>
    </VCard>

    <details v-if="status" class="ncm-device-details rounded border border-gray-200 bg-white">
      <summary class="cursor-pointer px-4 py-3 text-sm font-medium">设备与凭据详情</summary>
      <VDescription>
        <VDescriptionItem label="设备 ID" :content="status.deviceId" />
        <VDescriptionItem label="公钥版本" :content="String(status.xeapiKeyVersion ?? '-')" />
        <VDescriptionItem label="公钥获取时间" :content="fmt(status.xeapiKeyFetchedAt)" />
        <VDescriptionItem label="xeapi 会话" :content="status.xeapiSession ? '已建立' : '未建立'" />
        <VDescriptionItem label="NMTID" :content="status.nmtidReady ? '已获取' : '未获取'" />
        <VDescriptionItem
          label="密钥来源"
          :content="KEY_SOURCE[status.secretKeySource] ?? status.secretKeySource"
        />
        <VDescriptionItem label="上次保存" :content="fmt(status.persistedAt)" />
      </VDescription>
    </details>
  </div>
</template>

<style scoped>
.ncm-device {
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
