<script setup lang="ts">
import { VAlert, VButton, VDescription, VDescriptionItem } from '@halo-dev/components'
import { onMounted, ref } from 'vue'
import { meApi } from './meApi'
import { useLibraryRead } from '@/composables/libraryRead'

const { data: profile, loading, error, load } = useLibraryRead(meApi.profile)
const area = ref<HTMLElement>()

function retry() {
  area.value?.focus({ preventScroll: true })
  void load()
}

onMounted(load)
</script>

<template>
  <section ref="area" class="ncm-stats" tabindex="-1" aria-label="听歌概况">
    <p v-if="loading" class="ncm-stats__pending" role="status">正在读取听歌概况</p>
    <VAlert
      v-else-if="error"
      type="warning"
      :title="error.recovery === 'account' ? '网易云绑定需要处理' : '听歌概况读取失败'"
      :description="error.message"
      :closable="false"
    >
      <template v-if="error.recovery === 'retry'" #actions>
        <VButton size="sm" @click="retry">重试</VButton>
      </template>
    </VAlert>
    <VDescription v-else-if="profile">
      <VDescriptionItem label="网易云等级" :content="profile.level != null ? `Lv.${profile.level}` : '-'" />
      <VDescriptionItem label="累计听歌" :content="profile.listenSongs != null ? `${profile.listenSongs} 首` : '-'" />
      <VDescriptionItem v-if="profile.signature" label="签名" :content="profile.signature" />
    </VDescription>
  </section>
</template>

<style scoped>
.ncm-stats {
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid #f3f4f6;
  border-radius: 4px;
}
.ncm-stats__pending {
  margin: 0;
  padding: .75rem 1rem;
  color: #4b5563;
  font-size: .875rem;
}
.ncm-stats :deep(.description-item__content) {
  overflow-wrap: anywhere;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-stats :deep(button) {
    min-height: 44px;
  }
}
</style>
