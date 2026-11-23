<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  label: string
  items: readonly { id: string; label: string }[]
  disabled?: boolean
}>()

const selected = defineModel<string>({ required: true })
const group = ref<HTMLElement>()

function focusSelected() {
  group.value?.querySelector<HTMLButtonElement>('button[aria-pressed="true"]')?.focus()
}

defineExpose({ focusSelected })
</script>

<template>
  <div ref="group" class="ncm-tabs" role="group" :aria-label="label">
    <button
      v-for="item in items"
      :key="item.id"
      type="button"
      :aria-pressed="selected === item.id"
      :disabled="disabled"
      @click="selected = item.id"
    >
      {{ item.label }}
    </button>
  </div>
</template>

<style scoped>
.ncm-tabs {
  display: flex;
  flex-wrap: wrap;
  min-width: 0;
  gap: 4px;
  align-items: center;
  padding: 4px;
}
.ncm-tabs button {
  flex: none;
  min-height: 34px;
  padding: 4px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: #4b5563;
  font-size: 14px;
  white-space: nowrap;
}
.ncm-tabs button:hover {
  background: #f3f4f6;
}
.ncm-tabs button[aria-pressed='true'] {
  border-color: #cbd5e1;
  background: #e8edf3;
  color: #0f172a;
  font-weight: 600;
}
.ncm-tabs button:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.ncm-tabs button:disabled {
  cursor: wait;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-tabs button {
    min-height: 44px;
  }
}
</style>
