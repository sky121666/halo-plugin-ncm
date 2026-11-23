<script setup lang="ts">
import { IconClose, VButton } from '@halo-dev/components'
import { onMounted, ref, useId } from 'vue'

const props = defineProps<{
  title: string
  busy?: boolean
  flushBody?: boolean
  initialFocus?: string
}>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const heading = ref<HTMLElement>()
const body = ref<HTMLElement>()
const titleId = `ncm-modal-title-${useId()}`

function close() {
  dialog.value?.close()
}

function requestClose() {
  if (!props.busy) close()
}

function focusHeading(resetBodyScroll = false) {
  if (resetBodyScroll && body.value) body.value.scrollTop = 0
  heading.value?.focus({ preventScroll: true })
}

function cycleFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = Array.from(dialog.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), a[href], input:not(:disabled):not([type=hidden]), select:not(:disabled), textarea:not(:disabled)',
  )).filter((element) => element.tabIndex >= 0 && element.getClientRects().length > 0)
  const active = document.activeElement
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (!first || !last) {
    event.preventDefault()
    focusHeading()
  } else if (event.shiftKey && (active === first || active === heading.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

onMounted(() => {
  dialog.value?.showModal()
  const initial = props.initialFocus && dialog.value?.querySelector<HTMLElement>(props.initialFocus)
  if (initial) initial.focus({ preventScroll: true })
  else focusHeading()
})

defineExpose({ close, focusHeading })
</script>

<template>
  <dialog
    ref="dialog"
    class="ncm-modal"
    role="dialog"
    aria-modal="true"
    :aria-labelledby="titleId"
    :aria-busy="busy || undefined"
    @cancel.prevent="requestClose"
    @close="emit('close')"
    @keydown="cycleFocus"
  >
    <header class="ncm-modal__header">
      <h3 :id="titleId" ref="heading" tabindex="-1">{{ title }}</h3>
      <VButton size="sm" ghost aria-label="关闭" :disabled="busy" @click="requestClose">
        <template #icon><IconClose aria-hidden="true" /></template>
      </VButton>
    </header>
    <div ref="body" class="ncm-modal__body" :class="{ 'ncm-modal__body--flush': flushBody }">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="ncm-modal__footer">
      <slot name="footer" />
    </footer>
  </dialog>
</template>

<style scoped>
.ncm-modal {
  position: fixed;
  inset: 0;
  box-sizing: border-box;
  width: min(30rem, calc(100vw - 1.25rem));
  max-height: calc(100dvh - 1.25rem);
  margin: auto;
  padding: 0;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: .5rem;
  background: #fff;
  color: #111827;
  box-shadow: 0 12px 36px rgb(15 23 42 / 20%);
}
.ncm-modal[open] {
  display: flex;
  flex-direction: column;
}
.ncm-modal::backdrop {
  background: rgb(15 23 42 / 42%);
}
.ncm-modal__header,
.ncm-modal__footer {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: .5rem;
  padding: .625rem 1rem;
}
.ncm-modal__header {
  justify-content: space-between;
  border-bottom: 1px solid #e5e7eb;
}
.ncm-modal__header h3 {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
}
.ncm-modal__body {
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 1rem;
}
.ncm-modal__body--flush {
  padding: 0;
}
.ncm-modal__footer {
  border-top: 1px solid #e5e7eb;
}
@media (max-width: 640px), (pointer: coarse), (max-height: 440px) {
  .ncm-modal__header :deep(button),
  .ncm-modal__footer :deep(button) {
    min-width: 44px;
    min-height: 44px;
  }
}
@media (max-height: 300px) {
  .ncm-modal__header,
  .ncm-modal__footer {
    padding-block: .375rem;
  }
}
</style>
