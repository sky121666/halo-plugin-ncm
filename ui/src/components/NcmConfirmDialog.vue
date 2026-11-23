<script lang="ts">
export type ConfirmAction = {
  title: string
  description: string
  confirmText: string
  danger?: boolean
  run: () => Promise<void>
}
</script>

<script setup lang="ts">
import { VAlert, VButton } from '@halo-dev/components'
import { inject, ref, shallowRef, useId } from 'vue'
import { libraryProblem, openAccountBinding, type LibraryProblem } from '@/composables/libraryRead'
import NcmModal from './NcmModal.vue'

const props = defineProps<{ action: ConfirmAction }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<InstanceType<typeof NcmModal>>()
const descriptionId = `ncm-confirm-description-${useId()}`
const saving = ref(false)
const error = shallowRef<LibraryProblem>()
const openBinding = inject(openAccountBinding)

async function confirm() {
  if (saving.value || (error.value && error.value.recovery !== 'retry')) return
  error.value = undefined
  saving.value = true
  dialog.value?.focusHeading()
  try {
    await props.action.run()
    dialog.value?.close()
  } catch (cause) {
    error.value = libraryProblem(cause, '操作暂时失败，请稍后重试。')
    dialog.value?.focusHeading(true)
  } finally {
    saving.value = false
  }
}

function recoverBinding() {
  dialog.value?.close()
  void openBinding?.()
}
</script>

<template>
  <NcmModal
    ref="dialog"
    :title="action.title"
    :busy="saving"
    :aria-describedby="descriptionId"
    initial-focus="[data-ncm-cancel]"
    @close="emit('close')"
  >
    <p :id="descriptionId" class="ncm-confirm__description">{{ action.description }}</p>
    <VAlert
      v-if="error"
      class="ncm-confirm__error"
      role="alert"
      type="error"
      title="操作失败"
      :description="error.message"
      :closable="false"
    >
      <template v-if="error.recovery === 'account' && openBinding" #actions>
        <VButton size="sm" @click="recoverBinding">查看账号绑定</VButton>
      </template>
    </VAlert>
    <template #footer>
      <VButton
        :type="action.danger ? 'danger' : 'secondary'"
        :loading="saving"
        :disabled="saving || !!(error && error.recovery !== 'retry')"
        @click="confirm"
      >
        {{ action.confirmText }}
      </VButton>
      <VButton data-ncm-cancel :disabled="saving" @click="dialog?.close()">取消</VButton>
    </template>
  </NcmModal>
</template>

<style scoped>
.ncm-confirm__description {
  margin: 0;
  overflow-wrap: anywhere;
}
.ncm-confirm__error {
  margin-top: .75rem;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-confirm__error :deep(button) {
    min-height: 44px;
  }
}
</style>
