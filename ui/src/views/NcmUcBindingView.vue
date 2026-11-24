<script setup lang="ts">
import { VCard, VPageHeader } from '@halo-dev/components'
import { computed, ref } from 'vue'
import RiMusic2Line from '~icons/ri/music-2-line'
import { myBindingApi } from '@/api'
import type { NcmAccountView } from '@/api/generated'
import AccountBinding from '@/components/AccountBinding.vue'
import { musicPageUrl, pluginDisplayName } from '@/consoleNavigation'

const account = ref<NcmAccountView>()
const bound = computed(() => account.value?.bound === true && account.value.readable !== false)
</script>

<template>
  <VPageHeader :title="pluginDisplayName">
    <template #icon>
      <RiMusic2Line />
    </template>
    <template #actions>
      <a
        :href="bound ? '/music/library' : musicPageUrl"
        class="ncm-uc__music-link"
      >
        {{ bound ? '打开个人曲库' : '打开音乐页' }}
      </a>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard class="ncm-uc__account">
      <template #header>
        <h2 class="ncm-uc__account-title">网易云账号</h2>
      </template>
      <AccountBinding
        v-model:account="account"
        :api="myBindingApi"
        label="网易云账号"
        unbound-description="绑定后可在音乐页管理个人曲库。"
      />
    </VCard>
  </div>
</template>

<style scoped>
.ncm-uc__account {
  width: 100%;
  max-width: 48rem;
}
.ncm-uc__account-title {
  margin: 0;
  padding: .875rem 1rem;
  font-size: 14px;
  font-weight: 600;
}
.ncm-uc__music-link {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  padding: 0 8px;
  color: #374151;
  font-size: 14px;
  text-decoration: underline;
  text-underline-offset: 4px;
  white-space: nowrap;
}
.ncm-uc__music-link:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
</style>
