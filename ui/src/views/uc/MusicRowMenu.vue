<script setup lang="ts">
import { IconMore, VDropdown } from '@halo-dev/components'
import { nextTick, ref, useId } from 'vue'

type MenuAction = {
  label: string
  href?: string
  danger?: boolean
  onSelect?: (trigger: HTMLButtonElement) => void
}

defineProps<{
  label: string
  actions: MenuAction[]
}>()

const id = `ncm-row-menu-${useId()}`
const trigger = ref<HTMLButtonElement>()
const menu = ref<HTMLElement>()
const shown = ref(false)
let openAtEnd = false

function menuItems() {
  return Array.from(menu.value?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
}

function open(atEnd = false) {
  openAtEnd = atEnd
  shown.value = true
}

async function focusMenu() {
  await nextTick()
  if (!shown.value) return
  const items = menuItems()
  items[openAtEnd ? items.length - 1 : 0]?.focus({ preventScroll: true })
}

function close() {
  shown.value = false
  trigger.value?.focus({ preventScroll: true })
}

function toggle() {
  if (shown.value) close()
  else open()
}

function choose(action: MenuAction) {
  close()
  if (trigger.value) action.onSelect?.(trigger.value)
}

function navigateMenu(event: KeyboardEvent) {
  if (event.key === 'Tab') {
    close()
    return
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close()
    return
  }
  const items = menuItems()
  const current = items.indexOf(document.activeElement as HTMLElement)
  let next: number
  switch (event.key) {
    case 'ArrowDown': next = (current + 1) % items.length; break
    case 'ArrowUp': next = (current - 1 + items.length) % items.length; break
    case 'Home': next = 0; break
    case 'End': next = items.length - 1; break
    default: return
  }
  event.preventDefault()
  items[next]?.focus({ preventScroll: true })
}
</script>

<template>
  <VDropdown
    v-if="actions.length"
    v-model:shown="shown"
    :triggers="[]"
    :distance="6"
    placement="bottom-end"
    no-auto-focus
    @apply-show="focusMenu"
  >
    <button
      ref="trigger"
      type="button"
      class="ncm-row-menu__trigger"
      :aria-label="label"
      aria-haspopup="menu"
      :aria-expanded="shown"
      :aria-controls="shown ? id : undefined"
      title="更多操作"
      @click="toggle"
      @keydown.down.prevent="open()"
      @keydown.up.prevent="open(true)"
      @keydown.esc.stop.prevent="close"
    >
      <IconMore aria-hidden="true" />
    </button>
    <template #popper>
      <div :id="id" ref="menu" role="menu" :aria-label="label" class="ncm-row-menu__items" @keydown="navigateMenu">
        <template v-for="action in actions" :key="action.label">
          <a
            v-if="action.href"
            :href="action.href"
            target="_blank"
            rel="noopener"
            role="menuitem"
            tabindex="-1"
            class="ncm-row-menu__item"
            @click="close"
          >{{ action.label }}</a>
          <button
            v-else
            type="button"
            role="menuitem"
            tabindex="-1"
            class="ncm-row-menu__item"
            :class="{ 'ncm-row-menu__item--danger': action.danger }"
            @click="choose(action)"
          >{{ action.label }}</button>
        </template>
      </div>
    </template>
  </VDropdown>
</template>

<style scoped>
.ncm-row-menu__trigger {
  display: inline-grid;
  width: 2rem;
  height: 2rem;
  place-items: center;
  border-radius: .375rem;
}
.ncm-row-menu__trigger:hover,
.ncm-row-menu__trigger[aria-expanded="true"],
.ncm-row-menu__item:hover {
  background: #f3f4f6;
}
.ncm-row-menu__trigger:focus-visible,
.ncm-row-menu__item:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: -2px;
}
.ncm-row-menu__items {
  display: grid;
  min-width: 9rem;
  max-width: min(15rem, calc(100vw - 1.5rem));
  padding: .25rem;
}
.ncm-row-menu__item {
  display: flex;
  min-height: 2.25rem;
  align-items: center;
  padding: .375rem .625rem;
  border-radius: .25rem;
  font-size: .875rem;
  text-align: left;
}
.ncm-row-menu__item--danger {
  color: #b91c1c;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-row-menu__trigger {
    width: 44px;
    height: 44px;
  }
  .ncm-row-menu__item {
    min-height: 44px;
  }
}
</style>
