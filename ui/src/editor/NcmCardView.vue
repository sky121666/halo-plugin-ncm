<script setup lang="ts">
import { NodeViewWrapper, nodeViewProps } from '@halo-dev/richtext-editor'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CardPreview from './CardPreview.vue'
import NcmCardDialog from './NcmCardDialog.vue'
import { fetchCard, type CardData } from './card-api'
import { CARD_EDIT_EVENT } from './constants'
import { TYPE_LABELS, defaultTitle, isValidId, normalizeType, type NcmCardType } from './link'

const props = defineProps(nodeViewProps)

const type = computed<NcmCardType>(() => normalizeType(props.node.attrs.ncmType))
const id = computed(() => String(props.node.attrs.ncmId ?? ''))
const title = computed(() => String(props.node.attrs.title ?? ''))
const valid = computed(() => isValidId(id.value))

const data = ref<CardData | null>(null)
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
let saved = false

let seq = 0
async function load() {
  const my = ++seq
  data.value = null
  error.value = ''
  if (!valid.value) return
  loading.value = true
  try {
    const d = await fetchCard(type.value, id.value)
    if (my !== seq) return
    data.value = d
    // 这里不自动改写文章里的链接文字（只打开文章就改内容会让编辑器认为有未保存的修改），
    // 需要更新标题时点“修改”再保存一次
  } catch (e) {
    if (my === seq) error.value = e instanceof Error ? e.message : '预览失败'
  } finally {
    if (my === seq) loading.value = false
  }
}

watch([type, id], () => {
  load()
})

function openDialog() {
  if (!props.editor.isEditable) return
  saved = false
  dialog.value = true
}

function onSave(v: { type: NcmCardType; id: string; title: string }) {
  saved = true
  props.updateAttributes({ ncmType: v.type, ncmId: v.id, title: v.title })
}

function onClose() {
  dialog.value = false
  // 新插入的卡片没有填链接就取消：删掉这个空卡片
  if (!saved && !valid.value) {
    props.deleteNode()
  }
}

function remove() {
  props.deleteNode()
}

const root = ref<{ $el?: HTMLElement } | null>(null)
let rootEl: HTMLElement | undefined

onMounted(() => {
  rootEl = root.value?.$el
  rootEl?.addEventListener(CARD_EDIT_EVENT, openDialog)
  if (valid.value) load()
  else openDialog()
})

onBeforeUnmount(() => {
  rootEl?.removeEventListener(CARD_EDIT_EVENT, openDialog)
  seq++
})
</script>

<template>
  <node-view-wrapper ref="root" as="div" class="ncm-cv" :class="{ 'is-selected': selected }">
    <div class="ncm-cv__inner" contenteditable="false" @dblclick="openDialog">
      <CardPreview v-if="data" :data="data" compact />
      <div v-else class="ncm-cv__placeholder" :class="{ 'is-error': !!error }">
        <span class="ncm-cv__kind">网易云{{ TYPE_LABELS[type] }}</span>
        <span v-if="!valid">还没有填写链接</span>
        <span v-else-if="loading">正在获取预览…</span>
        <span v-else-if="error">{{ title || defaultTitle(type, id) }}：{{ error }}</span>
        <span v-else>{{ title || defaultTitle(type, id) }}</span>
      </div>
      <div v-if="editor.isEditable" class="ncm-cv__actions">
        <button type="button" class="ncm-cv__btn" title="修改" @click.stop="openDialog">修改</button>
        <button v-if="error" type="button" class="ncm-cv__btn" title="重新获取预览" @click.stop="load">重试</button>
        <button type="button" class="ncm-cv__btn is-danger" title="删除" @click.stop="remove">删除</button>
      </div>
    </div>
    <NcmCardDialog v-if="dialog" :type="type" :id="id" :title="title" @save="onSave" @close="onClose" />
  </node-view-wrapper>
</template>

<style scoped>
.ncm-cv {
  margin: 0.75em 0;
}
.ncm-cv__inner {
  position: relative;
  max-width: 640px;
  border-radius: 10px;
  outline: 2px solid transparent;
  outline-offset: 2px;
  transition: outline-color 0.15s;
}
.ncm-cv.is-selected .ncm-cv__inner {
  outline-color: #4f46e5;
}
.ncm-cv__placeholder {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-height: 64px;
  padding: 10px 12px;
  font-size: 14px;
  color: #6b7280;
  border: 1px dashed #d1d5db;
  border-radius: 10px;
  background: #fafafa;
}
.ncm-cv__placeholder.is-error {
  color: #dc2626;
}
.ncm-cv__kind {
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  color: #ec4141;
  border: 1px solid #ec4141;
  border-radius: 4px;
}
.ncm-cv__actions {
  position: absolute;
  top: 6px;
  right: 6px;
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}
.ncm-cv__inner:hover .ncm-cv__actions,
.ncm-cv.is-selected .ncm-cv__actions,
.ncm-cv__actions:focus-within {
  opacity: 1;
}
.ncm-cv__btn {
  padding: 2px 8px;
  font-size: 12px;
  line-height: 18px;
  color: #374151;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
}
.ncm-cv__btn:hover {
  background: #f3f4f6;
}
.ncm-cv__btn.is-danger {
  color: #dc2626;
}
</style>
