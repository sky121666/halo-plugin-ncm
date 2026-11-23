<script setup lang="ts">
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { inject, ref } from 'vue'
import { openAccountBinding, type LibraryProblem } from '@/composables/libraryRead'

const props = defineProps<{
  title: string
  summary?: string
  refreshing: boolean
  pending: boolean
  error?: LibraryProblem
}>()

const emit = defineEmits<{ refresh: [] }>()
const header = ref<HTMLElement>()
const heading = ref<HTMLElement>()
const openBinding = inject(openAccountBinding)

function focusHeading() {
  heading.value?.focus({ preventScroll: true })
  header.value?.scrollIntoView({ block: 'start' })
}

function refresh() {
  if (props.refreshing) return
  if (props.error) focusHeading()
  emit('refresh')
}

defineExpose({ focusHeading })
</script>

<template>
  <VCard :body-class="['!p-0', '!overflow-visible']">
    <template #header>
      <div ref="header" class="ncm-collection__heading">
        <div class="ncm-collection__title">
          <h3 ref="heading" tabindex="-1">{{ title }}</h3>
          <span v-if="summary" class="ncm-collection__summary">{{ summary }}</span>
        </div>
        <div class="ncm-collection__actions">
          <VButton v-if="!error" size="sm" :loading="refreshing" @click="refresh">
            刷新
          </VButton>
          <slot name="actions" />
        </div>
      </div>
    </template>

    <div v-if="$slots.toolbar" class="ncm-collection__toolbar">
      <slot name="toolbar" />
    </div>

    <div v-if="pending" role="status">
      <VLoading />
      <span class="sr-only">正在读取{{ title }}</span>
    </div>
    <div v-else-if="error" role="status">
      <VEmpty
        :title="error.recovery === 'account' ? '网易云绑定需要处理' : `${title}读取失败`"
        :message="error.message"
      >
        <template #actions>
          <VButton
            v-if="error.recovery === 'account'"
            size="sm"
            type="secondary"
            @click="openBinding?.()"
          >查看账号绑定</VButton>
          <VButton
            v-else-if="error.recovery === 'retry'"
            size="sm"
            :loading="refreshing"
            @click="refresh"
          >重试</VButton>
        </template>
      </VEmpty>
    </div>
    <slot v-else />
  </VCard>
</template>

<style scoped>
.ncm-collection__heading,
.ncm-collection__title,
.ncm-collection__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: .5rem;
}
.ncm-collection__heading {
  width: 100%;
  min-width: 0;
  justify-content: space-between;
  padding: .625rem 1rem;
  scroll-margin-top: .5rem;
}
.ncm-collection__heading h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.ncm-collection__summary {
  color: #4b5563;
  font-size: .8125rem;
}
.ncm-collection__toolbar {
  padding: .5rem .75rem;
  border-bottom: 1px solid #e5e7eb;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-collection__actions :deep(button),
  [role="status"] :deep(.empty-actions button) {
    min-height: 44px;
    min-width: 44px;
  }
}
</style>
