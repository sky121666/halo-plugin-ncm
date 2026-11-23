<script setup lang="ts">
import { Toast, VAlert, VButton, VEmpty, VSpace, VTag } from '@halo-dev/components'
import { computed, inject, nextTick, onMounted, ref, shallowRef } from 'vue'
import NcmConfirmDialog, { type ConfirmAction } from '@/components/NcmConfirmDialog.vue'
import MusicCollectionPanel from './MusicCollectionPanel.vue'
import SongList from './SongList.vue'
import SongPagination from './SongPagination.vue'
import { meApi, thumb, type MePlaylist, type MeSong } from './meApi'
import { useSongPagination } from './useSongPagination'
import { libraryProblem, openAccountBinding, type LibraryProblem } from '@/composables/libraryRead'

/** 一个歌单里的歌（分页）。自己创建的歌单可以移除歌曲；别人的歌单可以收藏 / 取消收藏。 */
const props = defineProps<{
  playlist: MePlaylist
}>()
const emit = defineEmits<{
  (e: 'back'): void
  /** 歌单内容或收藏状态变了，列表需要刷新 */
  (e: 'changed'): void
}>()

const { page, size, loading, error, data, panel, load } = useSongPagination(
  (offset, limit, signal) => meApi.playlistTracks(props.playlist.id, offset, limit, signal),
)
const subscribing = ref(false)
const subscribeError = shallowRef<LibraryProblem>()
const confirmation = shallowRef<{ action: ConfirmAction; trigger: HTMLButtonElement }>()
const playlistSummary = ref<HTMLElement>()
const openBinding = inject(openAccountBinding)

/** 优先用接口返回的最新信息 */
const info = computed<MePlaylist>(() => {
  const playlist = data.value?.playlist ?? props.playlist
  return { ...playlist, subscribed: playlist.subscribed ?? !playlist.own }
})
const items = computed(() => (data.value?.items ?? []).map((song) => ({ song })))

function remove(song: MeSong, trigger: HTMLButtonElement) {
  const playlist = info.value
  confirmation.value = {
    trigger,
    action: {
      title: '从歌单移除',
      description: `将「${song.name ?? song.id}」从「${playlist.name ?? '歌单'}」中移除。`,
      danger: true,
      confirmText: '移除',
      run: async () => {
        await meApi.removeTracks(playlist.id, [song.id])
        Toast.success('已移除')
        emit('changed')
        await load()
      },
    },
  }
}

async function closeConfirmation() {
  const trigger = confirmation.value?.trigger
  confirmation.value = undefined
  await nextTick()
  if (trigger?.isConnected) trigger.focus({ preventScroll: true })
  else panel.value?.focusHeading()
}

async function toggleSubscribe() {
  if (subscribing.value || loading.value || !data.value || error.value) return
  const next = !info.value.subscribed
  subscribing.value = true
  subscribeError.value = undefined
  try {
    await meApi.subscribe(info.value.id, next)
    Toast.success(next ? '已收藏歌单' : '已取消收藏')
    if (data.value) {
      data.value = { ...data.value, playlist: { ...data.value.playlist, subscribed: next } }
    }
    emit('changed')
  } catch (cause) {
    subscribeError.value = libraryProblem(cause, '暂时无法更新收藏，请重试。')
  } finally {
    subscribing.value = false
  }
}

function added(playlistId: string) {
  emit('changed')
  if (playlistId === info.value.id) void load()
}

onMounted(() => {
  playlistSummary.value?.focus()
})
</script>

<template>
  <MusicCollectionPanel
    ref="panel"
    title="歌单曲目"
    :refreshing="loading || subscribing"
    :pending="loading && !data"
    :error="error"
    @refresh="load"
  >
    <template #actions>
      <VButton size="sm" @click="emit('back')">返回歌单列表</VButton>
    </template>
    <template #toolbar>
      <div class="ncm-tracks__summary">
        <div class="ncm-tracks__info">
          <img v-if="info.coverUrl" :src="thumb(info.coverUrl, 160)" alt="" class="ncm-tracks__cover" />
          <div class="ncm-tracks__text">
            <h4 ref="playlistSummary" tabindex="-1" class="ncm-tracks__name">{{ info.name || `歌单 ${info.id}` }}</h4>
            <VSpace spacing="xs" class="ncm-tracks__tags">
              <VTag v-if="info.likedSongs" theme="primary">我喜欢的音乐</VTag>
              <VTag v-else-if="info.own" theme="secondary">我创建的</VTag>
              <VTag v-else>{{ info.creatorName || '其他用户' }}</VTag>
              <VTag v-if="info.privacy">私密</VTag>
              <span class="ncm-tracks__count">{{ data?.total ?? info.trackCount ?? 0 }} 首</span>
            </VSpace>
          </div>
        </div>
        <div class="ncm-tracks__actions">
          <VButton
            v-if="!info.own"
            size="sm"
            :loading="subscribing"
            :disabled="loading || !!error || !data"
            @click="toggleSubscribe"
          >
            {{ info.subscribed === false ? '收藏歌单' : '取消收藏' }}
          </VButton>
          <a v-if="!info.privacy" :href="info.sitePath" target="_blank" rel="noopener" class="ncm-tracks__link">在音乐页打开</a>
        </div>
      </div>
      <VAlert
        v-if="subscribeError"
        class="m-1"
        type="error"
        title="收藏操作失败"
        :description="subscribeError.message"
        :closable="false"
      >
        <template v-if="subscribeError.recovery === 'account'" #actions>
          <VButton size="sm" @click="openBinding?.()">查看账号绑定</VButton>
        </template>
      </VAlert>
    </template>
    <VEmpty v-if="items.length === 0" title="歌单暂无曲目" />
    <template v-else>
      <SongList
        :items="items"
        :start-index="(data?.offset ?? 0) + 1"
        :removable="info.own"
        @remove="remove"
        @added="added"
      />
      <SongPagination
        v-model:page="page"
        v-model:size="size"
        :total="data?.total ?? 0"
        :loading="loading || subscribing"
      />
    </template>
  </MusicCollectionPanel>
  <NcmConfirmDialog v-if="confirmation" :action="confirmation.action" @close="closeConfirmation" />
</template>

<style scoped>
.ncm-tracks__summary,
.ncm-tracks__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.ncm-tracks__summary {
  padding: 4px;
  justify-content: space-between;
}
.ncm-tracks__info {
  display: flex;
  flex: 1 1 14rem;
  min-width: 0;
  align-items: center;
  gap: 12px;
}
.ncm-tracks__cover {
  width: 56px;
  height: 56px;
  flex: none;
  border-radius: 6px;
  object-fit: cover;
}
.ncm-tracks__text {
  min-width: 0;
}
.ncm-tracks__name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.ncm-tracks__tags {
  margin-top: 4px;
  flex-wrap: wrap;
}
.ncm-tracks__count {
  color: #6b7280;
  font-size: 12px;
}
.ncm-tracks__link {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  color: #2563eb;
  font-size: 13px;
  text-decoration: underline;
  text-underline-offset: 3px;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-tracks__actions :deep(button),
  .ncm-tracks__link {
    min-height: 44px;
  }
}
</style>
