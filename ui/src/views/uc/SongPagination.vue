<script setup lang="ts">
import { VButton } from '@halo-dev/components'
import { computed } from 'vue'

const props = defineProps<{ total: number; loading: boolean }>()
const page = defineModel<number>('page', { required: true })
const size = defineModel<number>('size', { required: true })
const sizes = [20, 50, 100] as const
const pageCount = computed(() => Math.max(1, Math.ceil(props.total / size.value)))
const pageSize = computed({
  get: () => size.value,
  set: (value: number) => {
    size.value = value
    page.value = 1
  },
})
</script>

<template>
  <nav v-if="total > sizes[0]" class="ncm-song-pagination" aria-label="曲目分页">
    <div class="ncm-song-pagination__pages">
      <VButton size="sm" :disabled="loading || page <= 1" @click="page -= 1">上一页</VButton>
      <select v-model.number="page" aria-label="跳转页码" :disabled="loading">
        <option v-for="index in pageCount" :key="index" :value="index">
          {{ index }} / {{ pageCount }} 页
        </option>
      </select>
      <VButton size="sm" :disabled="loading || page >= pageCount" @click="page += 1">下一页</VButton>
    </div>
    <label class="ncm-song-pagination__size">
      每页
      <select v-model.number="pageSize" aria-label="每页曲目数" :disabled="loading">
        <option v-for="option in sizes" :key="option" :value="option">{{ option }} 首</option>
      </select>
    </label>
  </nav>
</template>

<style scoped>
.ncm-song-pagination,
.ncm-song-pagination__pages,
.ncm-song-pagination__size {
  display: flex;
  align-items: center;
  gap: .5rem;
}
.ncm-song-pagination {
  flex-wrap: wrap;
  justify-content: flex-end;
  padding: .5rem 1rem;
  border-top: 1px solid #f3f4f6;
}
.ncm-song-pagination select {
  min-height: 2rem;
  padding: .25rem;
  border: 1px solid #d1d5db;
  border-radius: .25rem;
  background: #fff;
  color: #1f2937;
  font-size: .875rem;
}
.ncm-song-pagination__size {
  color: #4b5563;
  font-size: .875rem;
}
@media (max-width: 640px) {
  .ncm-song-pagination {
    justify-content: space-between;
  }
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-song-pagination select,
  .ncm-song-pagination :deep(button) {
    min-height: 44px;
  }
}
</style>
