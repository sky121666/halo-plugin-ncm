<script setup lang="ts">
import { submitForm } from '@formkit/core'
import { Toast, VAlert, VButton } from '@halo-dev/components'
import { inject, nextTick, ref, shallowRef } from 'vue'
import NcmModal from '@/components/NcmModal.vue'
import { meApi, PRIVACY_PRIVATE, type MePlaylist } from './meApi'
import { libraryProblem, openAccountBinding, type LibraryProblem } from '@/composables/libraryRead'

/** 新建歌单：名称 + 是否隐私。 */
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'created', playlist: MePlaylist): void
}>()

const formId = `ncm-create-playlist-${Math.random().toString(36).slice(2, 8)}`
const modal = ref<InstanceType<typeof NcmModal>>()
const form = ref<{ name: string; private: boolean }>({ name: '', private: false })
const saving = ref(false)
const error = shallowRef<LibraryProblem>()
const openBinding = inject(openAccountBinding)

async function save() {
  if (saving.value || (error.value && error.value.recovery !== 'retry')) return
  error.value = undefined
  saving.value = true
  modal.value?.focusHeading()
  try {
    const p = await meApi.createPlaylist((form.value.name ?? '').trim(), form.value.private ? PRIVACY_PRIVATE : 0)
    Toast.success('歌单已创建')
    emit('created', p)
    modal.value?.close()
  } catch (cause) {
    error.value = libraryProblem(cause, '暂时无法创建歌单，请重试。')
    modal.value?.focusHeading(true)
  } finally {
    saving.value = false
  }
}

async function focusInvalidName() {
  await nextTick()
  const formElement = document.getElementById(formId)
  const nameInput = formElement?.querySelector<HTMLInputElement>('input[name="name"]')
  const message = formElement?.querySelector('[data-message-type="validation"]')
  nameInput?.focus({ preventScroll: true })
  const visibleTarget = message ?? nameInput
  visibleTarget?.scrollIntoView({ block: 'nearest' })
}

function recoverBinding() {
  modal.value?.close()
  void openBinding?.()
}
</script>

<template>
  <NcmModal ref="modal" title="新建歌单" :busy="saving" initial-focus="input[name='name']" @close="emit('close')">
    <VAlert
      v-if="error"
      class="mb-3"
      role="alert"
      type="error"
      title="新建歌单失败"
      :description="error.message"
      :closable="false"
    >
      <template v-if="error.recovery === 'account'" #actions>
        <VButton size="sm" @click="recoverBinding">查看账号绑定</VButton>
      </template>
    </VAlert>
    <!-- @vue-ignore -->
    <FormKit
      :id="formId"
      v-model="form"
      type="form"
      :actions="false"
      :disabled="saving"
      :config="{ validationVisibility: 'submit' }"
      @submit="save"
      @submit-invalid="focusInvalidName"
    >
      <FormKit
        name="name"
        label="歌单名称"
        type="text"
        :validation="[['required'], ['length', 1, 40]]"
        :validation-messages="{ length: '歌单名最多 40 个字' }"
      />
      <FormKit name="private" label="设为私密歌单" type="checkbox" help="只有自己可见" />
    </FormKit>
    <template #footer>
      <VButton
        type="secondary"
        :loading="saving"
        :disabled="saving || !!(error && error.recovery !== 'retry')"
        @click="submitForm(formId)"
      >创建</VButton>
      <VButton :disabled="saving" @click="modal?.close()">取消</VButton>
    </template>
  </NcmModal>
</template>
