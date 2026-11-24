<script setup lang="ts">
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CardPreview from './CardPreview.vue'
import { CardError, fetchCard, type CardData } from './card-api'
import { CARD_TYPES, TYPE_LABELS, canonicalUrl, isValidId, normalizeType, parseNcmInput, type NcmCardType } from './link'

const props = withDefaults(
  defineProps<{
    type?: string
    id?: string
    title?: string
  }>(),
  { type: 'song', id: '', title: '' },
)

const emit = defineEmits<{
  (e: 'save', value: { type: NcmCardType; id: string; title: string }): void
  (e: 'close'): void
}>()

const modal = ref<InstanceType<typeof VModal> | null>(null)
const inputEl = ref<HTMLTextAreaElement | null>(null)
const editing = computed(() => isValidId(props.id))

const input = ref(editing.value ? canonicalUrl(normalizeType(props.type), props.id) : '')
const idType = ref<NcmCardType>(normalizeType(props.type))
const parsed = computed(() => parseNcmInput(input.value, idType.value))
const isPureId = computed(() => parsed.value.ok && parsed.value.from === 'id')

const preview = ref<CardData | null>(null)
const previewKey = ref('')
const loading = ref(false)
const error = ref('')
const saving = ref(false)

const currentKey = computed(() => (parsed.value.ok ? `${parsed.value.type}:${parsed.value.id}` : ''))
const hint = computed(() => {
  const p = parsed.value
  if (p.ok) return `识别为${TYPE_LABELS[p.type]}，ID ${p.id}`
  return p.reason === 'empty' ? '' : p.message
})

let seq = 0
async function load(): Promise<CardData | null> {
  const p = parsed.value
  if (!p.ok) return null
  const key = `${p.type}:${p.id}`
  if (previewKey.value === key && preview.value) return preview.value
  const my = ++seq
  loading.value = true
  error.value = ''
  try {
    const data = await fetchCard(p.type, p.id)
    if (my === seq) {
      preview.value = data
      previewKey.value = key
    }
    return data
  } catch (e) {
    if (my === seq) {
      preview.value = null
      previewKey.value = ''
      error.value = e instanceof Error ? e.message : '预览失败'
    }
    throw e
  } finally {
    if (my === seq) loading.value = false
  }
}

// 输入停下 0.6 秒后自动预览
let timer: ReturnType<typeof setTimeout> | undefined
watch(currentKey, (key) => {
  clearTimeout(timer)
  error.value = ''
  if (!key) {
    preview.value = null
    previewKey.value = ''
    return
  }
  if (key === previewKey.value) return
  timer = setTimeout(() => {
    load().catch(() => {
      // 错误已经显示在弹窗里
    })
  }, 600)
})

async function save() {
  const p = parsed.value
  if (!p.ok) {
    error.value = p.message
    return
  }
  saving.value = true
  try {
    let title = ''
    try {
      const data = await load()
      title = data?.title || ''
    } catch (e) {
      if (e instanceof CardError && e.status === 404) {
        // 不存在：不插入，让站长检查链接
        return
      }
      // 网易云暂时不可用：先插入，标题用默认文字，访客看到卡片时会重新取数据
      title = props.title && editing.value && props.id === p.id ? props.title : ''
      Toast.warning('暂时取不到标题，先用默认文字；访客打开文章时卡片会正常显示')
    }
    emit('save', { type: p.type, id: p.id, title })
    modal.value?.close()
  } finally {
    saving.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    save()
  }
}

onMounted(() => {
  setTimeout(() => inputEl.value?.focus(), 50)
  if (editing.value) {
    load().catch(() => {
      // 错误已经显示在弹窗里
    })
  }
})

onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <VModal
    ref="modal"
    :title="editing ? '编辑音乐卡片' : '插入音乐卡片'"
    :width="600"
    mount-to-body
    @close="emit('close')"
  >
    <div class="ncm-cd">
      <label class="ncm-cd__label" for="ncm-card-input">网易云链接、分享文案或数字 ID</label>
      <textarea
        id="ncm-card-input"
        ref="inputEl"
        v-model.trim="input"
        class="ncm-cd__input"
        rows="3"
        placeholder="例如 https://music.163.com/#/song?id=186016，或者在网易云 App 里“分享 → 复制链接”后粘贴"
        @keydown="onKeydown"
      ></textarea>
      <p class="ncm-cd__tip">
        支持歌曲、歌单、专辑。163cn.tv 短链接请先在浏览器里打开，再复制 music.163.com 开头的长链接。
      </p>

      <div class="ncm-cd__types" role="radiogroup" aria-label="只填数字 ID 时的类型">
        <span class="ncm-cd__types-label">只填数字时的类型：</span>
        <label v-for="t in CARD_TYPES" :key="t" class="ncm-cd__type">
          <input v-model="idType" type="radio" name="ncm-card-type" :value="t" />
          {{ TYPE_LABELS[t] }}
        </label>
      </div>

      <p v-if="hint" class="ncm-cd__hint" :class="{ 'is-error': !parsed.ok }">
        {{ hint }}<template v-if="parsed.ok && !isPureId">（类型按链接识别）</template>
      </p>

      <div class="ncm-cd__preview">
        <div v-if="loading" class="ncm-cd__status">正在获取预览…</div>
        <div v-else-if="error" class="ncm-cd__status is-error">{{ error }}</div>
        <CardPreview v-else-if="preview && previewKey === currentKey" :data="preview" />
        <div v-else class="ncm-cd__status">识别出链接后会自动显示预览</div>
      </div>
    </div>
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="saving" :disabled="!parsed.ok" @click="save">
          {{ editing ? '保存' : '插入' }}
        </VButton>
        <VButton :disabled="!parsed.ok || loading" @click="load().catch(() => {})">重新预览</VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.ncm-cd {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
  color: #111827;
}
.ncm-cd__label {
  font-weight: 500;
}
.ncm-cd__input {
  box-sizing: border-box;
  width: 100%;
  padding: 8px 10px;
  font: inherit;
  line-height: 1.5;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  resize: vertical;
  outline: none;
}
.ncm-cd__input:focus {
  border-color: #4f46e5;
  box-shadow: 0 0 0 1px #4f46e5;
}
.ncm-cd__tip,
.ncm-cd__types-label {
  margin: 0;
  font-size: 12px;
  color: #6b7280;
}
.ncm-cd__types {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.ncm-cd__type {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}
.ncm-cd__hint {
  margin: 0;
  font-size: 13px;
  color: #059669;
}
.ncm-cd__hint.is-error,
.ncm-cd__status.is-error {
  color: #dc2626;
}
.ncm-cd__preview {
  min-height: 86px;
}
.ncm-cd__status {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 86px;
  font-size: 13px;
  color: #6b7280;
  border: 1px dashed #d1d5db;
  border-radius: 10px;
}
</style>
