<script setup lang="ts">
import { Toast, VAlert, VButton, VLoading, VTag } from '@halo-dev/components'
import { computed, inject, onMounted, ref, shallowRef } from 'vue'
import NcmModal from '@/components/NcmModal.vue'
import { meApi, thumb, type MePlaylist } from './meApi'
import { libraryProblem, openAccountBinding, useLibraryRead, type LibraryProblem } from '@/composables/libraryRead'

/** 把一首或几首歌加入自己创建的歌单。 */
const props = defineProps<{
  songIds: string[]
}>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'added', playlist: MePlaylist): void
}>()

const modal = ref<InstanceType<typeof NcmModal>>()
const { data, loading, error, load } = useLibraryRead(meApi.playlists)
const playlists = computed(() => (data.value?.items ?? []).filter((playlist) => playlist.own))
const writeError = shallowRef<LibraryProblem>()
const saving = ref<string>()
const openBinding = inject(openAccountBinding)

function retry() {
  modal.value?.focusHeading()
  void load()
}

function recoverBinding() {
  modal.value?.close()
  void openBinding?.()
}

async function add(p: MePlaylist) {
  if (saving.value || (writeError.value && writeError.value.recovery !== 'retry')) return
  saving.value = p.id
  writeError.value = undefined
  modal.value?.focusHeading()
  try {
    await meApi.addTracks(p.id, props.songIds)
    Toast.success(`已加入「${p.name ?? '歌单'}」`)
    emit('added', p)
    modal.value?.close()
  } catch (cause) {
    writeError.value = libraryProblem(cause, '暂时无法加入歌单，请重试。')
    modal.value?.focusHeading(true)
  } finally {
    saving.value = undefined
  }
}

onMounted(load)
</script>

<template>
  <NcmModal ref="modal" title="加入歌单" :busy="!!saving" flush-body @close="emit('close')">
    <div v-if="loading" role="status">
      <VLoading />
      <span class="sr-only">正在读取可选歌单</span>
    </div>
    <div v-else-if="error" class="ncm-picker__empty">
      <p role="status">{{ error.message }}</p>
      <VButton v-if="error.recovery === 'account'" size="sm" @click="recoverBinding">查看账号绑定</VButton>
      <VButton v-else-if="error.recovery === 'retry'" size="sm" @click="retry">重试</VButton>
    </div>
    <div v-else-if="playlists.length === 0" class="ncm-picker__empty">
      请先在「我的歌单」新建歌单。
    </div>
    <template v-else>
      <VAlert
        v-if="writeError"
        class="m-4"
        role="alert"
        type="error"
        title="加入歌单失败"
        :description="writeError.message"
        :closable="false"
      >
        <template v-if="writeError.recovery === 'account'" #actions>
          <VButton size="sm" @click="recoverBinding">查看账号绑定</VButton>
        </template>
      </VAlert>
      <ul class="ncm-picker">
        <li v-for="p in playlists" :key="p.id">
          <button
            type="button"
            class="ncm-picker__item"
            :aria-label="`加入歌单「${p.name || p.id}」`"
            :disabled="!!saving || !!(writeError && writeError.recovery !== 'retry')"
            @click="add(p)"
          >
            <img v-if="p.coverUrl" :src="thumb(p.coverUrl, 80)" alt="" class="ncm-picker__cover" loading="lazy" />
            <span v-else class="ncm-picker__cover ncm-picker__cover--blank" />
            <span class="ncm-picker__info">
              <span class="ncm-picker__name">{{ p.name || `歌单 ${p.id}` }}</span>
              <span class="ncm-picker__meta">
                <span>{{ p.trackCount ?? 0 }} 首</span>
                <VTag v-if="p.likedSongs" theme="primary">我喜欢的音乐</VTag>
                <VTag v-if="p.privacy">私密</VTag>
              </span>
            </span>
            <VLoading v-if="saving === p.id" class="ncm-picker__spin" />
          </button>
        </li>
      </ul>
    </template>
    <template #footer>
      <VButton :disabled="!!saving" @click="modal?.close()">取消</VButton>
    </template>
  </NcmModal>
</template>

<style scoped>
.ncm-picker {
  margin: 0;
  padding: 0;
  list-style: none;
}
.ncm-picker__item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border: 0;
  border-bottom: 1px solid #f3f4f6;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.ncm-picker__item:hover {
  background: #f9fafb;
}
.ncm-picker__item:disabled {
  cursor: wait;
}
.ncm-picker__cover {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 4px;
  object-fit: cover;
}
.ncm-picker__cover--blank {
  display: inline-block;
  background: #f3f4f6;
}
.ncm-picker__info {
  flex: 1;
  min-width: 0;
}
.ncm-picker__name {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
  font-size: 14px;
}
.ncm-picker__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: .25rem .5rem;
  margin-top: .25rem;
  color: #4b5563;
  font-size: 12px;
}
.ncm-picker__spin {
  flex: none;
}
.ncm-picker__empty {
  padding: 24px 16px;
  color: #6b7280;
  font-size: 14px;
  text-align: center;
}
.ncm-picker__empty p {
  margin: 0 0 .75rem;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-picker__empty :deep(button) {
    min-height: 44px;
  }
}
</style>
