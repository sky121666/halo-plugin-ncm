<script setup lang="ts">
import { Toast, VAlert, VButton } from '@halo-dev/components'
import { isAxiosError } from 'axios'
import { computed, onMounted, ref, watch } from 'vue'
import { ncmApiClient, ncmWrite } from '@/api'
import type { NcmConfigSnapshot } from '@/api/generated'
import {
  applyEdits, bindPositionDrag, buttonPosition, defaultPosition, moveWithKey, readPositions,
  type Layout, type Point, type Positions,
} from '../../../frontstage/mini-player/placement'

const clientId = 'mini-player'
const api = ncmApiClient.clientConfig
const choices = [{ id: 'wide', label: '宽屏' }, { id: 'compact', label: '窄屏' }] as const
const canPublish = ref(false)
const saved = ref<NcmConfigSnapshot>()
const draft = ref<Positions>({})
const layout = ref<Layout>('wide')
const dragging = ref<Point>()
const disc = ref<HTMLButtonElement>()
const preview = ref<HTMLElement>()
const saving = ref(false)
const loading = ref(false)
const conflict = ref(false)
const error = ref('')
const changed = computed(() => !choices.every(({ id }) => {
  const a = draft.value[id]
  const b = readPositions(saved.value?.config)[id]
  return a?.x === b?.x && a?.y === b?.y
}))
const position = computed(() => dragging.value ?? draft.value[layout.value] ?? defaultPosition)
const discStyle = computed(() => ({
  left: `calc(10px + (100% - 60px) * ${position.value.x})`,
  top: `calc(10px + (100% - 60px) * ${position.value.y})`,
}))

function apply(data: NcmConfigSnapshot) {
  saved.value = data
  draft.value = readPositions(data.config)
  conflict.value = false
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    apply((await api.getNcmConfigDefaults({ clientId })).data)
  } catch {
    error.value = '默认位置读取失败。'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    canPublish.value = (await api.getNcmMyConfig({ clientId })).data.canPublishSite === true
    if (canPublish.value) await load()
  } catch {
    canPublish.value = false
  }
})

function change(point: Point | null) {
  draft.value = applyEdits(draft.value, { [layout.value]: point })
}

function asPosition(point: Point) {
  const rect = preview.value!.getBoundingClientRect()
  return buttonPosition(point, {
    left: rect.left + 10, top: rect.top + 10, right: rect.right - 10, bottom: rect.bottom - 10,
  }, { width: 40, height: 40 })
}

watch(disc, (button, _, onCleanup) => {
  if (!button) return
  const controller = new AbortController()
  bindPositionDrag(button, {
    origin: () => {
      if (saving.value) return
      const rect = button.getBoundingClientRect()
      return { x: rect.left, y: rect.top }
    },
    move: (pixels) => { dragging.value = asPosition(pixels) },
    finish: (pixels) => {
      dragging.value = undefined
      if (pixels && preview.value) change(asPosition(pixels))
    },
  }, controller.signal)
  onCleanup(() => controller.abort())
}, { flush: 'post' })

function moveByKey(event: KeyboardEvent) {
  if (saving.value || !disc.value) return
  const origin = disc.value.getBoundingClientRect()
  const point = moveWithKey(event, { x: origin.left, y: origin.top })
  if (!point) return
  event.preventDefault()
  change(asPosition(point))
}

async function publish() {
  if (!saved.value || !changed.value || saving.value || conflict.value) return
  saving.value = true
  error.value = ''
  try {
    const { data } = await api.publishNcmConfigDefaults({
      clientId, ...ncmWrite,
      ncmConfigWrite: {
        schemaVersion: saved.value.schemaVersion,
        revision: saved.value.revision,
        config: draft.value,
      },
    })
    apply(data)
    Toast.success('已保存站点默认位置')
  } catch (reason) {
    conflict.value = isAxiosError(reason) && reason.response?.status === 409
    error.value = conflict.value ? '默认位置已在别处更新，请载入最新设置。' : '默认位置保存失败，请重试。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section v-if="canPublish" aria-labelledby="ncm-widget-placement-title" class="space-y-3">
    <div>
      <h3 id="ncm-widget-placement-title" class="text-sm font-medium">默认悬浮位置</h3>
      <p class="mt-1 text-xs text-gray-600">拖动预览中的黑胶，设置访客首次看到的位置。</p>
    </div>
    <VAlert v-if="error" :type="conflict ? 'warning' : 'error'" :title="error" :closable="false">
      <template v-if="conflict || !saved" #actions>
        <VButton size="sm" :loading="loading" @click="load">载入最新设置</VButton>
      </template>
    </VAlert>
    <template v-if="saved">
      <div class="flex flex-wrap gap-2" role="group" aria-label="默认位置预览布局">
        <VButton
          v-for="item in choices" :key="item.id" size="sm"
          :type="layout === item.id ? 'secondary' : 'default'"
          :aria-pressed="layout === item.id" :disabled="saving || !!dragging"
          @click="layout = item.id"
        >{{ item.label }}</VButton>
      </div>
      <div ref="preview" class="placement-preview" :data-layout="layout">
        <button
          ref="disc" class="placement-disc" :class="{ dragging }" type="button" :style="discStyle"
          :disabled="saving" aria-label="默认黑胶位置，可拖动或用方向键调整"
          @keydown="moveByKey"
        ><span aria-hidden="true"></span></button>
      </div>
      <div class="flex flex-wrap justify-end gap-2">
        <VButton size="sm" :disabled="saving || !draft[layout]" @click="change(null)">使用右下角</VButton>
        <VButton size="sm" type="secondary" :loading="saving" :disabled="!changed || conflict" @click="publish">
          保存为站点默认
        </VButton>
      </div>
    </template>
  </section>
</template>

<style scoped>
.placement-preview {
  position: relative;
  width: 100%;
  height: 182px;
  overflow: hidden;
  border-radius: 14px;
  background: radial-gradient(ellipse at 13% 88%, #78acda 0%, transparent 59%),
    radial-gradient(ellipse at 91% 5%, #b4cae8 0%, transparent 55%), #26384e;
}
.placement-preview[data-layout='compact'] {
  width: min(100%, 180px);
  height: 240px;
  margin-inline: auto;
}
.placement-disc {
  position: absolute;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 1px solid #d9e2f04d;
  border-radius: 50%;
  background: linear-gradient(145deg, #edf3fc45, transparent 55%), #22262d;
  box-shadow: 0 3px 12px #060a1268;
  touch-action: none;
  cursor: grab;
}
.placement-disc.dragging { cursor: grabbing; }
.placement-disc:focus-visible { outline: 2px solid #eaf4ff; outline-offset: 2px; }
.placement-disc span {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: radial-gradient(circle, #15161b 0 1px, #c2b49e 1.4px 6.7px, transparent 7.2px),
    repeating-radial-gradient(circle, transparent 0 2.4px, #e8edf52b 2.7px, transparent 3.2px),
    conic-gradient(from 28deg, #111217 0deg, #373940 36deg, #111217 72deg 174deg,
      #41444b 210deg, #111217 248deg 360deg);
  box-shadow: inset 0 0 0 1px #ffffff14;
}
</style>
