<script setup lang="ts">
import { submitForm } from '@formkit/core'
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VCard,
  VDropdown,
  VDropdownItem,
  vClosePopper,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VModal,
  VSpace,
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ncmConsoleApiClient, NCM_REQUEST_HEADER } from '@/api'
import type { NcmFeaturedItem, NcmFeaturedPreview, NcmFeaturedTrack } from '@/api/generated'
import { miniPlayerSettingsRoute, musicPageUrl } from '@/consoleNavigation'
import {
  errorText,
  estimateLabel,
  estimateTheme,
  moveName,
  playButtonText,
  previewDotState,
  previewStateText,
  stateInfo,
  statsSummary,
} from './featured'

/*
 * 歌单发布：粘贴网易云分享链接 → 识别并预览（游客视角的可播放预估）→ 编辑标题简介 → 保存草稿或发布 → 排序 / 下架。
 * 只有已发布并且游客能公开访问的歌单才会出现在 /music，顺序和这里一致。
 */

const INPUT_ID = 'ncm-featured-input'
const ADD_FORM_ID = 'ncm-featured-add-metadata'
const EDIT_FORM_ID = 'ncm-featured-edit-metadata'
const featuredApi = ncmConsoleApiClient.featuredPlaylist
const writeOptions = { headers: { [NCM_REQUEST_HEADER]: '1' } }
const ACTIONS = {
  publish: { method: 'publishNcmFeaturedPlaylist', message: '歌单已发布到音乐页' },
  unpublish: { method: 'unpublishNcmFeaturedPlaylist', message: '已下架' },
  check: { method: 'checkNcmFeaturedPlaylist', message: '已重新核对' },
} as const

const items = ref<NcmFeaturedItem[]>([])
const loading = ref(false)
const loaded = ref(false)
const busy = ref<string>('')
const panelError = ref('')
const canEditSettings = computed(() => utils.permission.has(['system:plugins:manage']))
const listLocked = computed(() => !loaded.value || loading.value || !!busy.value || !!panelError.value)

const fmt = (v?: string | null) => (v ? utils.date.format(v) : '-')

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    items.value = (await featuredApi.listNcmFeaturedPlaylists()).data.items ?? []
    loaded.value = true
    panelError.value = ''
  } catch (e) {
    panelError.value = errorText(e, '读取歌单列表失败')
  } finally {
    loading.value = false
  }
}

function replaceItem(item: NcmFeaturedItem) {
  const i = items.value.findIndex((x) => x.name === item.name)
  if (i >= 0) items.value.splice(i, 1, item)
  else items.value.push(item)
}

// ---------- 添加：识别并预览 ----------

const adding = ref(false)
const publishOnSubmit = ref(false)
const input = ref('')
const resolving = ref(false)
const resolveMessage = ref('')
const addError = ref('')
const preview = ref<NcmFeaturedPreview>()
const previewLoading = ref(false)
const probing = ref(false)
const showAllTracks = ref(false)
const form = ref({ title: '', description: '' })

const visibleTracks = computed(() => {
  const list = preview.value?.tracks ?? []
  return showAllTracks.value ? list : list.slice(0, 15)
})
const canPublish = computed(() => preview.value?.state === 'ok')
const addBusy = computed(
  () => resolving.value || previewLoading.value || probing.value || busy.value === 'add',
)
const addVisible = computed({
  get: () => adding.value,
  set: (visible: boolean) => {
    if (!visible && !addBusy.value) closeAdd()
  },
})

async function openAdd() {
  if (listLocked.value) return
  adding.value = true
  await nextTick()
  document.getElementById(INPUT_ID)?.focus()
}

function submitAdd(publish: boolean) {
  if (addBusy.value) return
  publishOnSubmit.value = publish
  submitForm(ADD_FORM_ID)
}

async function recognize() {
  if (addBusy.value) return
  const text = input.value.trim()
  if (!text) {
    resolveMessage.value = '请输入网易云歌单的分享链接、分享文案或 ID'
    return
  }
  resolving.value = true
  clearPreview()
  try {
    const { data: r } = await featuredApi.resolveNcmFeaturedPlaylist(
      { ncmFeaturedResolveRequest: { input: text } },
      writeOptions,
    )
    if (!r.ok || !r.playlistId) {
      resolveMessage.value = r.message || '没有识别出歌单'
      return
    }
    await loadPreview(r.playlistId, false)
  } catch (e) {
    resolveMessage.value = errorText(e, '识别失败')
  } finally {
    resolving.value = false
  }
}

async function loadPreview(id: string, probe: boolean) {
  if (probe) probing.value = true
  else previewLoading.value = true
  try {
    const { data: p } = await featuredApi.previewNcmFeaturedPlaylist({
      id,
      probe: probe || undefined,
    })
    preview.value = p
    if (!probe) {
      showAllTracks.value = false
      form.value = { title: '', description: '' }
    }
  } catch (e) {
    resolveMessage.value = errorText(e, '预览失败')
  } finally {
    previewLoading.value = false
    probing.value = false
  }
}

function clearPreview() {
  preview.value = undefined
  resolveMessage.value = ''
  addError.value = ''
  publishOnSubmit.value = false
  audioError.value = ''
  stopAudio()
}

function closeAdd() {
  adding.value = false
  input.value = ''
  clearPreview()
}

async function save(publish: boolean) {
  publishOnSubmit.value = false
  const p = preview.value
  if (!p || busy.value || addBusy.value || p.existing || (publish && !canPublish.value)) return
  busy.value = 'add'
  addError.value = ''
  try {
    const { data: item } = await featuredApi.createNcmFeaturedPlaylist(
      {
        ncmFeaturedSaveRequest: { playlistId: p.playlistId, ...form.value, publish },
      },
      writeOptions,
    )
    replaceItem(item)
    Toast.success(publish ? '歌单已发布到音乐页' : '已保存为草稿')
    closeAdd()
  } catch (e) {
    const msg = errorText(e, '保存失败')
    // 发布被拒绝时后端已经保存了草稿（409 的说明里会写），刷新列表；记录已存在就收起添加区
    await load()
    if (items.value.some((x) => x.playlistId === p.playlistId)) {
      Toast.warning(msg)
      closeAdd()
    } else {
      addError.value = msg
    }
  } finally {
    busy.value = ''
  }
}

// ---------- 列表：发布、下架、核对、删除、排序 ----------

async function act(item: NcmFeaturedItem, action: keyof typeof ACTIONS) {
  if (listLocked.value) return
  busy.value = item.name
  try {
    const { method, message } = ACTIONS[action]
    const { data } = await featuredApi[method]({ name: item.name }, writeOptions)
    replaceItem(data)
    Toast.success(message)
  } catch (e) {
    Toast.error(errorText(e))
    await load()
  } finally {
    busy.value = ''
  }
}

function remove(item: NcmFeaturedItem) {
  if (listLocked.value) return
  Dialog.warning({
    title: '移除精选歌单',
    description: `将「${item.displayTitle}」从本站精选歌单移除。`,
    confirmType: 'danger',
    confirmText: '移除',
    onConfirm: async () => {
      if (listLocked.value) return
      busy.value = item.name
      try {
        await featuredApi.deleteNcmFeaturedPlaylist({ name: item.name }, writeOptions)
        items.value = items.value.filter((x) => x.name !== item.name)
        Toast.success('已移除精选歌单')
      } catch (e) {
        Toast.error(errorText(e, '移除精选歌单失败'))
      } finally {
        busy.value = ''
      }
    },
  })
}

async function move(item: NcmFeaturedItem, delta: -1 | 1) {
  if (listLocked.value) return
  const names = moveName(
    items.value.map((x) => x.name),
    item.name,
    delta,
  )
  if (!names) return
  busy.value = 'order'
  try {
    items.value = (
      await featuredApi.reorderNcmFeaturedPlaylists(
        { ncmFeaturedReorderRequest: { names } },
        writeOptions,
      )
    ).data.items
    await nextTick()
    document.querySelector<HTMLElement>(`[data-featured="${item.name}"]`)?.focus()
  } catch (e) {
    Toast.error(errorText(e, '调整顺序失败'))
    await load()
  } finally {
    busy.value = ''
  }
}

// ---------- 编辑 ----------

const editing = ref<NcmFeaturedItem>()
const editForm = ref({ title: '', description: '' })
const editVisible = computed({
  get: () => !!editing.value,
  set: (v: boolean) => {
    if (!v && !busy.value) editing.value = undefined
  },
})

function edit(item: NcmFeaturedItem) {
  if (listLocked.value) return
  editing.value = item
  editForm.value = { title: item.title ?? '', description: item.description ?? '' }
}

async function saveEdit() {
  const item = editing.value
  if (!item || busy.value) return
  busy.value = item.name
  try {
    const { data: next } = await featuredApi.updateNcmFeaturedPlaylist(
      {
        name: item.name,
        ncmFeaturedSaveRequest: editForm.value,
      },
      writeOptions,
    )
    replaceItem(next)
    editing.value = undefined
    Toast.success('已保存展示信息')
  } catch (e) {
    Toast.error(errorText(e, '保存失败'))
  } finally {
    busy.value = ''
  }
}

// ---------- 预览里试听：走插件的播放地址，按当前登录账号取址（管理员为站长账号），按钮从不禁用 ----------

const audio = typeof Audio === 'function' ? new Audio() : undefined
const playing = ref('')
const paused = ref(true)
const audioError = ref('')

function toggleTrack(t: NcmFeaturedTrack) {
  if (!audio) return
  audioError.value = ''
  if (playing.value === t.id) {
    if (audio.paused) {
      audio.play().catch(() => onPlayFailed(t))
    } else {
      audio.pause()
    }
    return
  }
  playing.value = t.id
  audio.src = t.playUrl
  audio.play().catch(() => onPlayFailed(t))
}

function onPlayFailed(t: NcmFeaturedTrack) {
  if (playing.value !== t.id) return
  paused.value = true
  audioError.value = `《${t.name}》播放失败。可重试或试听其他曲目。`
}

function stopAudio() {
  playing.value = ''
  paused.value = true
  if (audio?.hasAttribute('src')) {
    audio.pause()
    audio.removeAttribute('src')
    audio.load()
  }
}

function onTracksToggle(event: Event) {
  if (event.target instanceof HTMLDetailsElement && !event.target.open) stopAudio()
}

audio?.addEventListener('play', () => (paused.value = false))
audio?.addEventListener('pause', () => (paused.value = true))
audio?.addEventListener('ended', () => {
  paused.value = true
  playing.value = ''
})
audio?.addEventListener('error', () => {
  if (playing.value) audioError.value = '当前曲目播放失败。可重试或试听其他曲目。'
  paused.value = true
  playing.value = ''
})

onMounted(load)
watch(() => input.value.trim(), clearPreview)
onBeforeUnmount(stopAudio)
</script>

<template>
  <section class="min-w-0 max-w-3xl" aria-labelledby="ncm-featured-list-title">
    <VModal v-model:visible="addVisible" title="添加精选歌单" :width="720" :layer-closable="false">
      <div v-if="adding">
        <div class="ncm-add__row">
          <FormKit
            :id="INPUT_ID"
            v-model="input"
            type="textarea"
            label="网易云歌单链接或 ID"
            :rows="2"
            :delay="0"
            :disabled="addBusy"
            placeholder="粘贴分享链接、分享文案或歌单 ID"
            :classes="{ outer: '!pb-0 min-w-0' }"
            @keydown.enter.exact.prevent="recognize"
          />
          <VButton
            class="ncm-add__btn"
            type="secondary"
            :loading="resolving || previewLoading"
            :disabled="addBusy"
            @click="recognize"
          >
            识别并预览
          </VButton>
        </div>
        <p v-if="resolveMessage" class="ncm-add__message" role="alert">{{ resolveMessage }}</p>

        <div v-if="preview" class="ncm-preview">
          <div class="ncm-preview__head">
            <img
              v-if="preview.coverUrl"
              :src="preview.coverUrl"
              alt=""
              class="ncm-preview__cover"
            />
            <div class="ncm-preview__info">
              <div class="ncm-preview__title">
                {{ preview.name || `歌单 ${preview.playlistId}` }}
              </div>
              <div class="ncm-muted">
                <span v-if="preview.creator">{{ preview.creator }} · </span>
                <span v-if="preview.trackCount != null">{{ preview.trackCount }} 首 · </span>
                <span>ID {{ preview.playlistId }}</span>
              </div>
              <VStatusDot
                :state="previewDotState(preview.state)"
                :text="previewStateText(preview.state)"
              />
              <div v-if="preview.message" class="ncm-muted">{{ preview.message }}</div>
            </div>
          </div>

          <VAlert
            v-if="preview.existing"
            type="warning"
            :title="`歌单已在列表中 · ${preview.existing.published ? '已发布' : '草稿'}`"
            :closable="false"
          />

          <div class="ncm-stats">
            <div>
              <span class="ncm-stats__label">游客视角</span>
              <span>{{ statsSummary(preview.guest) }}</span>
            </div>
            <div v-if="preview.siteBound">
              <span class="ncm-stats__label">站长账号</span>
              <span>{{ statsSummary(preview.site) }}</span>
            </div>
            <p class="ncm-muted">预估基于曲目权限。试听使用当前登录账号。</p>
          </div>

          <details v-if="preview.tracks.length" class="ncm-track-details" @toggle="onTracksToggle">
            <summary>曲目与试听</summary>
            <VEntityContainer class="ncm-featured-entries">
              <VEntity v-for="t in visibleTracks" :key="t.id">
                <template #start>
                  <VEntityField
                    :title="t.name"
                    :description="[t.artists, t.album].filter(Boolean).join(' · ')"
                  />
                </template>
                <template #end>
                  <VEntityField>
                    <template #description>
                      <VSpace class="ncm-preview__actions">
                        <VTag
                          :theme="estimateTheme(t.guest)"
                          title="游客视角，按版权信息的静态预估"
                        >
                          游客预估：{{ estimateLabel(t.guest) }}
                        </VTag>
                        <VTag
                          v-if="t.measured"
                          :theme="estimateTheme(t.measured)"
                          title="用游客身份实际取址的结果"
                        >
                          游客实测：{{ estimateLabel(t.measured) }}
                        </VTag>
                        <span class="ncm-muted">{{ t.duration }}</span>
                        <VButton
                          size="sm"
                          :aria-label="`${playButtonText(t.id, playing, paused)}：${t.name}`"
                          :aria-pressed="playing === t.id && !paused"
                          @click="toggleTrack(t)"
                        >
                          {{ playButtonText(t.id, playing, paused) }}
                        </VButton>
                      </VSpace>
                    </template>
                  </VEntityField>
                </template>
              </VEntity>
            </VEntityContainer>
            <p v-if="audioError" class="ncm-muted" role="status">{{ audioError }}</p>
            <VSpace class="ncm-preview__actions">
              <VButton
                v-if="preview.tracks.length > 15"
                size="sm"
                @click="showAllTracks = !showAllTracks"
              >
                {{ showAllTracks ? '收起' : `展开前 ${preview.tracks.length} 首` }}
              </VButton>
              <VButton
                v-if="preview.state === 'ok'"
                size="sm"
                :loading="probing"
                :disabled="addBusy"
                @click="loadPreview(preview.playlistId, true)"
              >
                游客实测前 10 首
              </VButton>
            </VSpace>
          </details>

          <FormKit
            v-if="!preview.existing"
            :id="ADD_FORM_ID"
            v-model="form"
            type="form"
            :actions="false"
            :config="{ validationVisibility: 'submit' }"
            @submit-invalid="publishOnSubmit = false"
            @submit="save(publishOnSubmit)"
          >
            <FormKit
              name="title"
              type="text"
              label="显示标题"
              :placeholder="preview.name || ''"
              help="留空沿用网易云歌单名。"
              validation="length:0,80"
            />
            <FormKit
              name="description"
              type="textarea"
              label="展示简介"
              :rows="3"
              help="留空沿用网易云歌单简介。"
              validation="length:0,500"
            />
          </FormKit>
        </div>
      </div>
      <template #footer>
        <div class="w-full space-y-2">
          <VAlert v-if="addError" type="error" :title="addError" :closable="false" />
          <div class="flex flex-wrap justify-end gap-2">
            <template v-if="preview && !preview.existing">
              <VButton :disabled="addBusy" @click="submitAdd(false)">保存草稿</VButton>
              <VButton
                type="secondary"
                :loading="busy === 'add'"
                :disabled="addBusy || !canPublish"
                @click="submitAdd(true)"
              >
                发布到音乐页
              </VButton>
            </template>
            <VButton :disabled="addBusy" @click="closeAdd">取消</VButton>
          </div>
        </div>
      </template>
    </VModal>

    <VCard role="region" class="min-w-0" aria-labelledby="ncm-featured-list-title" :aria-busy="loading">
      <template #header>
        <div class="flex min-h-12 w-full flex-wrap items-center justify-between gap-2 px-4 py-2">
          <div class="min-w-0">
            <h2 id="ncm-featured-list-title" class="font-medium">精选歌单</h2>
            <p class="text-sm text-gray-600">已发布的歌单展示在音乐页，也可供站点小播放器使用。</p>
          </div>
          <div class="flex flex-wrap items-center gap-3">
            <a :href="musicPageUrl" target="_blank" rel="noopener" class="text-sm underline underline-offset-4">
              查看音乐页
            </a>
            <VButton type="secondary" size="sm" :disabled="listLocked" @click="openAdd">添加歌单</VButton>
          </div>
        </div>
      </template>
      <VAlert
        v-if="panelError"
        type="error"
        :title="panelError"
        :description="loaded ? '当前显示上次读取结果。' : undefined"
        :closable="false"
        class="mb-3"
      >
        <template #actions>
          <VButton size="sm" :loading="loading" @click="load">重试</VButton>
        </template>
      </VAlert>
      <VLoading v-if="!loaded && !panelError" />
      <VEmpty
        v-else-if="loaded && !panelError && !items.length"
        title="暂无精选歌单"
        message="添加网易云歌单后，可保存草稿或发布到音乐页。"
      />
      <template v-else-if="items.length">
        <p v-if="items.length > 1" class="mb-3 text-sm text-gray-600">
          可在「更多」调整展示顺序，或聚焦条目后按 Alt + ↑ / ↓。
        </p>
        <VEntityContainer class="ncm-featured-entries">
          <VEntity
            v-for="(item, i) in items"
            :key="item.name"
            :data-featured="item.name"
            tabindex="0"
            class="ncm-row"
            :aria-label="`第 ${i + 1} 个：${item.displayTitle}`"
            @keydown.alt.up.self.prevent="move(item, -1)"
            @keydown.alt.down.self.prevent="move(item, 1)"
          >
            <template #start>
              <img v-if="item.coverUrl" :src="item.coverUrl" alt="" class="ncm-row__cover" />
              <VEntityField :title="item.displayTitle">
                <template #description>
                  <div class="ncm-row__desc">
                    <VStatusDot :state="stateInfo(item).state" :text="stateInfo(item).text" />
                    <span class="ncm-muted">游客：{{ statsSummary(item.guest) }}</span>
                    <span class="ncm-muted">核对 {{ fmt(item.checkedAt) }}</span>
                    <span v-if="item.lastError" class="ncm-muted">{{ item.lastError }}</span>
                  </div>
                </template>
              </VEntityField>
            </template>
            <template #end>
              <VEntityField>
                <template #description>
                  <div class="ncm-row__actions">
                    <VButton
                      v-if="canEditSettings && item.published && item.state === 'ok'"
                      size="sm"
                      :disabled="listLocked"
                      :route="miniPlayerSettingsRoute(item.playlistId)"
                      :aria-label="`配置小播放器 ${item.displayTitle}`"
                    >
                      配置小播放器
                    </VButton>
                    <VButton size="sm" :disabled="listLocked" @click="edit(item)">编辑展示</VButton>
                    <VButton
                      v-if="!item.published"
                      size="sm"
                      type="secondary"
                      :disabled="listLocked"
                      @click="act(item, 'publish')"
                    >
                      发布
                    </VButton>
                    <VDropdown placement="bottom-end" :disabled="listLocked">
                      <VButton
                        size="sm"
                        :aria-label="`更多操作 ${item.displayTitle}`"
                        :disabled="listLocked"
                      >
                        更多
                      </VButton>
                      <template #popper>
                        <VDropdownItem v-close-popper :disabled="i === 0" @click="move(item, -1)"
                          >上移</VDropdownItem
                        >
                        <VDropdownItem
                          v-close-popper
                          :disabled="i === items.length - 1"
                          @click="move(item, 1)"
                          >下移</VDropdownItem
                        >
                        <VDropdownItem v-close-popper @click="act(item, 'check')"
                          >检查访客访问</VDropdownItem
                        >
                        <VDropdownItem
                          v-if="item.published"
                          v-close-popper
                          @click="act(item, 'unpublish')"
                          >下架</VDropdownItem
                        >
                        <VDropdownItem v-close-popper type="danger" @click="remove(item)"
                          >移除</VDropdownItem
                        >
                      </template>
                    </VDropdown>
                  </div>
                </template>
              </VEntityField>
            </template>
          </VEntity>
        </VEntityContainer>
      </template>
    </VCard>

    <VModal v-model:visible="editVisible" title="编辑展示信息" :width="560">
      <FormKit
        v-if="editing"
        :id="EDIT_FORM_ID"
        v-model="editForm"
        type="form"
        :actions="false"
        :config="{ validationVisibility: 'submit' }"
        @submit="saveEdit"
      >
        <FormKit
          name="title"
          type="text"
          label="显示标题"
          :placeholder="editing.neteaseName || ''"
          help="留空沿用网易云歌单名。"
          validation="length:0,80"
        />
        <FormKit
          name="description"
          type="textarea"
          label="展示简介"
          :rows="4"
          help="留空沿用网易云歌单简介。"
          validation="length:0,500"
        />
      </FormKit>
      <template #footer>
        <VSpace class="flex-wrap">
          <VButton
            type="secondary"
            :loading="!!editing && busy === editing.name"
            @click="submitForm(EDIT_FORM_ID)"
          >
            保存展示信息
          </VButton>
          <VButton :disabled="!!busy" @click="editing = undefined">取消</VButton>
        </VSpace>
      </template>
    </VModal>
  </section>
</template>

<style scoped>
.ncm-track-details > summary {
  cursor: pointer;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 500;
}
.ncm-add__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 8px;
}
.ncm-add__message {
  margin: 8px 0 0;
  font-size: 13px;
  color: #dc2626;
}
.ncm-preview {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 12px;
}
.ncm-preview__head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.ncm-preview__cover {
  width: 96px;
  height: 96px;
  flex: 0 0 96px;
  border-radius: 6px;
  object-fit: cover;
}
.ncm-preview__info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.ncm-preview__title {
  font-size: 16px;
  font-weight: 600;
  word-break: break-word;
}
.ncm-stats {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}
.ncm-stats__label {
  display: inline-block;
  min-width: 5em;
  color: #6b7280;
}
.ncm-muted {
  margin: 0;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.6;
}
.ncm-featured-entries :deep(.ncm-row:focus-visible) {
  outline: 2px solid #4f46e5;
  outline-offset: -2px;
}
.ncm-row__cover {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 4px;
  object-fit: cover;
}
.ncm-row__desc {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  align-items: center;
}
.ncm-row__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}
@media (max-width: 640px) {
  .ncm-add__row {
    grid-template-columns: minmax(0, 1fr);
  }
  .ncm-add__btn {
    justify-self: end;
  }
  .ncm-preview__actions {
    flex-wrap: wrap;
  }
  .ncm-featured-entries :deep(table) {
    table-layout: fixed;
  }
  .ncm-featured-entries :deep(.entity-wrapper) {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }
  .ncm-featured-entries :deep(.entity-start-wrapper),
  .ncm-featured-entries :deep(.entity-end-wrapper) {
    width: 100%;
    min-width: 0;
  }
  .ncm-featured-entries :deep(.entity-end-wrapper) {
    padding-top: 0;
  }
  .ncm-featured-entries :deep(.entity-end) {
    justify-content: flex-start;
  }
  .ncm-featured-entries :deep(.entity-field-wrapper) {
    min-width: 0;
    max-width: 100%;
  }
  .ncm-featured-entries :deep(.entity-field-description-body) {
    white-space: normal;
  }
  .ncm-row__actions {
    justify-content: flex-start;
  }
}
</style>
