<script setup lang="ts">
import {
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
import { RouterLink } from 'vue-router'
import { ncmConsoleApiClient, siteAccountApi } from '@/api'
import type { NcmGuestStatus } from '@/api/generated'
import AccountBinding from '@/components/AccountBinding.vue'
import { maintenanceTabId, pluginSettingsRoute } from '@/consoleNavigation'
import { formatGuestAttempt, guestStatusLabels } from '@/guestStatus'

const guest = ref<NcmGuestStatus>()
const loading = ref(false)
const failed = ref(false)
const canMaintain = computed(() =>
  utils.permission.has(['system:plugins:view', 'system:plugins:manage'], false),
)

async function loadGuest() {
  if (loading.value) return
  loading.value = true
  failed.value = false
  try {
    guest.value = (await ncmConsoleApiClient.guestStatus.getNcmGuestStatus()).data
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(loadGuest)
</script>

<template>
  <section aria-labelledby="ncm-accounts-title" class="max-w-3xl">
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h2 id="ncm-accounts-title" class="font-medium">账号与游客</h2>
      <nav v-if="canMaintain" aria-label="账号与游客相关入口" class="text-sm">
        <RouterLink
          :to="pluginSettingsRoute(maintenanceTabId)"
          class="text-gray-700 underline underline-offset-4"
        >
          维护
        </RouterLink>
      </nav>
    </div>

    <div class="grid grid-cols-1 gap-4">
      <VCard class="min-w-0" role="region" aria-labelledby="ncm-site-account-title">
        <template #header>
          <h3 id="ncm-site-account-title" class="flex min-h-12 items-center px-4 py-2 font-medium">
            站长账号
          </h3>
        </template>
        <AccountBinding
          :api="siteAccountApi"
          label="站长网易云账号"
          unbound-description="绑定后可用于展示站长的公开歌单与资料。"
        />
      </VCard>

      <VCard
        class="min-w-0"
        role="region"
        aria-labelledby="ncm-guest-session-title"
        :aria-busy="loading"
      >
        <template #header>
          <div class="flex min-h-12 w-full flex-wrap items-center justify-between gap-2 px-4 py-2">
            <h3 id="ncm-guest-session-title" class="font-medium">游客会话</h3>
            <VButton size="sm" :loading="loading" :disabled="loading" @click="loadGuest">
              刷新状态
            </VButton>
          </div>
        </template>
        <VAlert
          v-if="failed"
          type="error"
          title="游客状态读取失败"
          :description="guest ? '当前显示上次读取结果。' : undefined"
          :closable="false"
          class="mb-3"
        />
        <VLoading v-if="!guest && !failed" />
        <VDescription v-if="guest">
          <VDescriptionItem label="注册状态">
            <VStatusDot v-bind="guestStatusLabels[guest.state]" />
          </VDescriptionItem>
          <VDescriptionItem
            label="最近初始化尝试"
            :content="formatGuestAttempt(guest.lastAttempt)"
          />
        </VDescription>
      </VCard>
    </div>
  </section>
</template>
