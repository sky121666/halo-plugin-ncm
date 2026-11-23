<script setup lang="ts">
import {
  Toast,
  VButton,
  VEmpty,
  VEntity,
  VEntityContainer,
  VTag,
} from '@halo-dev/components'
import { computed, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import MusicTabs from '@/components/MusicTabs.vue'
import NcmConfirmDialog, { type ConfirmAction } from '@/components/NcmConfirmDialog.vue'
import CreatePlaylistModal from './CreatePlaylistModal.vue'
import MusicCollectionPanel from './MusicCollectionPanel.vue'
import MusicRowMenu from './MusicRowMenu.vue'
import PlaylistTracks from './PlaylistTracks.vue'
import { meApi, thumb, type MePlaylist } from './meApi'
import { useLibraryRead } from '@/composables/libraryRead'

/** 我的歌单：创建的、收藏的，“我喜欢的音乐”置顶；点开看歌单里的歌。 */
const { data, loading, error, load } = useLibraryRead(meApi.playlists)
const playlists = computed(() => data.value?.items ?? [])
const selected = ref<MePlaylist>()
const creating = ref(false)
const confirmation = shallowRef<{ action: ConfirmAction; trigger: HTMLButtonElement }>()
const listPanel = ref<{ focusHeading: () => void }>()
const route = useRoute()
const router = useRouter()
const playlistQuery = computed(() => route.query.playlist)

const FILTERS = [
  { id: 'all', label: '全部', emptyTitle: '暂无歌单' },
  { id: 'own', label: '我创建的', emptyTitle: '暂无创建的歌单' },
  { id: 'sub', label: '我收藏的', emptyTitle: '暂无收藏的歌单' },
]
const filter = computed({
  get: () => FILTERS.find(({ id }) => id === route.query.playlistFilter)?.id ?? 'all',
  set: (value: string) => {
    void router.replace({ query: { ...route.query, playlistFilter: value === 'all' ? undefined : value } })
  },
})

const shown = computed(() =>
  playlists.value.filter((p) => (filter.value === 'own' ? p.own : filter.value === 'sub' ? !p.own : true)),
)
const emptyTitle = computed(() => FILTERS.find((item) => item.id === filter.value)?.emptyTitle ?? '暂无歌单')

function playlistLocation(id: string) {
  return {
    query: { ...route.query, tab: 'playlists', playlist: id },
    state: { ncmPlaylistFromList: true },
  }
}

function playlistLinkId(id: string) {
  return `ncm-uc-tracks-${id}`
}

function backToPlaylists() {
  if (router.options.history.state.ncmPlaylistFromList === true) {
    router.back()
  } else {
    void router.replace({ query: { ...route.query, playlist: undefined } })
  }
}

watch([playlistQuery, playlists], ([id, list]) => {
  if (typeof id !== 'string') selected.value = undefined
  else if (selected.value?.id !== id) selected.value = list.find((playlist) => playlist.id === id)
}, { immediate: true })

watch(playlistQuery, async (id, previous) => {
  if (id !== undefined || previous === undefined) return
  await nextTick()
  const link = typeof previous === 'string' && document.getElementById(playlistLinkId(previous))
  if (link) link.focus()
  else listPanel.value?.focusHeading()
})

function playlistActions(p: MePlaylist) {
  return [
    ...(!p.privacy ? [{ label: '在音乐页打开', href: p.sitePath }] : []),
    ...(p.own && !p.likedSongs
      ? [{ label: '删除歌单', danger: true, onSelect: (trigger: HTMLButtonElement) => remove(p, trigger) }]
      : []),
    ...(!p.own
      ? [{ label: '取消收藏', onSelect: (trigger: HTMLButtonElement) => unsubscribe(p, trigger) }]
      : []),
  ]
}

function remove(p: MePlaylist, trigger: HTMLButtonElement) {
  confirmation.value = {
    trigger,
    action: {
      title: '删除歌单',
      description: `「${p.name ?? p.id}」将在网易云音乐中删除，无法恢复。`,
      danger: true,
      confirmText: '删除',
      run: async () => {
        await meApi.deletePlaylist(p.id)
        Toast.success('歌单已删除')
        await load()
      },
    },
  }
}

function unsubscribe(p: MePlaylist, trigger: HTMLButtonElement) {
  confirmation.value = {
    trigger,
    action: {
      title: '取消收藏',
      description: `「${p.name ?? p.id}」将从当前网易云账号的收藏中移除。`,
      confirmText: '取消收藏',
      run: async () => {
        await meApi.subscribe(p.id, false)
        Toast.success('已取消收藏')
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
  else listPanel.value?.focusHeading()
}

function created() {
  filter.value = 'own'
  load()
}

async function closeCreate() {
  creating.value = false
  await nextTick()
  document.getElementById('ncm-create-playlist')?.focus()
}

onMounted(load)
</script>

<template>
  <PlaylistTracks
    v-if="selected"
    :key="selected.id"
    :playlist="selected"
    @back="backToPlaylists"
    @changed="load"
  />

  <MusicCollectionPanel
    v-else
    ref="listPanel"
    title="我的歌单"
    :summary="playlistQuery === undefined && !error && shown.length ? `${shown.length} 个` : undefined"
    :refreshing="loading"
    :pending="loading && playlists.length === 0"
    :error="error"
    @refresh="load"
  >
    <template #actions>
      <VButton v-if="playlistQuery !== undefined" size="sm" @click="backToPlaylists">返回歌单列表</VButton>
      <VButton v-else id="ncm-create-playlist" size="sm" type="secondary" @click="creating = true">新建歌单</VButton>
    </template>
    <template v-if="playlistQuery === undefined" #toolbar>
      <MusicTabs v-model="filter" label="歌单类型" :items="FILTERS" />
    </template>
    <VEmpty
      v-if="playlistQuery !== undefined"
      title="此歌单已不在列表中"
      message="返回列表后可查看当前创建和收藏的歌单。"
    />
    <VEmpty v-else-if="shown.length === 0" :title="emptyTitle" />
    <VEntityContainer v-else class="ncm-lists__table">
      <VEntity v-for="p in shown" :key="p.id" class="ncm-lists__row">
        <template #start>
          <RouterLink
            :id="playlistLinkId(p.id)"
            :to="playlistLocation(p.id)"
            :title="p.name || `歌单 ${p.id}`"
            class="ncm-lists__entry"
          >
            <img v-if="p.coverUrl" :src="thumb(p.coverUrl, 96)" alt="" class="ncm-lists__cover" loading="lazy" />
            <span v-else class="ncm-lists__cover ncm-lists__cover--blank" />
            <span class="ncm-lists__info">
              <span class="ncm-lists__name">{{ p.name || `歌单 ${p.id}` }}</span>
              <span class="ncm-lists__meta">
                <span>{{ p.trackCount ?? 0 }} 首</span>
                <span v-if="!p.own && p.creatorName">{{ p.creatorName }}</span>
                <VTag v-if="p.likedSongs" theme="primary">我喜欢的音乐</VTag>
                <VTag v-else-if="!p.own">已收藏</VTag>
                <VTag v-if="p.privacy">私密</VTag>
              </span>
            </span>
          </RouterLink>
        </template>
        <template #end>
          <MusicRowMenu :label="`歌单「${p.name || p.id}」的更多操作`" :actions="playlistActions(p)" />
        </template>
      </VEntity>
    </VEntityContainer>
  </MusicCollectionPanel>

  <CreatePlaylistModal v-if="creating" @close="closeCreate" @created="created" />
  <NcmConfirmDialog v-if="confirmation" :action="confirmation.action" @close="closeConfirmation" />
</template>

<style scoped>
.ncm-lists__cover {
  display: block;
  flex: none;
  width: 48px;
  height: 48px;
  border-radius: 4px;
  object-fit: cover;
}
.ncm-lists__cover--blank {
  background: #f3f4f6;
}
.ncm-lists__entry {
  display: flex;
  min-height: 48px;
  min-width: 0;
  align-items: center;
  gap: .75rem;
  border-radius: .25rem;
}
.ncm-lists__entry:hover .ncm-lists__name {
  text-decoration: underline;
}
.ncm-lists__entry:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 3px;
}
.ncm-lists__info {
  min-width: 0;
}
.ncm-lists__name {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
  font-size: .875rem;
  font-weight: 600;
}
.ncm-lists__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: .25rem .5rem;
  margin-top: .25rem;
  color: #4b5563;
  font-size: .75rem;
  overflow-wrap: anywhere;
}
@media (max-width: 640px) {
  .ncm-lists__table :deep(.entity-wrapper.ncm-lists__row) {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
  }
  .ncm-lists__table :deep(.ncm-lists__row .entity-end-wrapper) {
    width: auto;
    padding: 0 .5rem 0 0;
  }
}
</style>
