<script setup lang="ts">
import {
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VTag,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, onMounted, ref } from 'vue'
import MusicTabs from '@/components/MusicTabs.vue'
import MusicCollectionPanel from './MusicCollectionPanel.vue'
import SongList from './SongList.vue'
import { meApi, thumb } from './meApi'
import { useLibraryRead, type LibraryProblem } from '@/composables/libraryRead'

/** 最近播放：歌曲 / 歌单（网易云各保留最近 100 条）。 */
const TABS = [
  { id: 'songs', label: '歌曲' },
  { id: 'playlists', label: '歌单' },
]
const tab = ref<string>('songs')
const { data, loading, error, load } = useLibraryRead(meApi.recent)

const songs = computed(() =>
  (data.value?.songs ?? []).map((r, i) => ({
    song: r.song,
    meta: r.playedAt ? utils.date.format(r.playedAt) : undefined,
    key: `${r.song.id}-${i}`,
  })),
)
const playlists = computed(() =>
  (data.value?.playlists ?? []).map(({ playlist, playedAt }) => ({
    playlist,
    playedAt,
    name: playlist.name || `歌单 ${playlist.id}`,
    description: [
      typeof playlist.trackCount === 'number' ? `${playlist.trackCount} 首` : undefined,
      playlist.creatorName,
    ].filter(Boolean).join(' · '),
  })),
)
const listError = computed<LibraryProblem | undefined>(() =>
  error.value || (tab.value === 'playlists' && data.value?.playlistsUnavailable
    ? { message: '暂时无法读取歌单播放记录，请重试。', recovery: 'retry' }
    : undefined),
)

onMounted(load)
</script>

<template>
  <MusicCollectionPanel
    title="最近播放"
    :refreshing="loading"
    :pending="loading && !data"
    :error="listError"
    @refresh="load"
  >
    <template #toolbar>
      <MusicTabs v-model="tab" label="最近播放类型" :items="TABS" />
    </template>
    <template v-if="tab === 'songs'">
      <VEmpty v-if="songs.length === 0" title="暂无歌曲播放记录" />
      <SongList v-else :items="songs" />
    </template>
    <VEmpty v-else-if="playlists.length === 0" title="暂无歌单播放记录" />
    <VEntityContainer v-else>
      <VEntity v-for="(r, i) in playlists" :key="`${r.playlist.id}-${i}`">
        <template #start>
          <VEntityField>
            <template #description>
              <img
                v-if="r.playlist.coverUrl"
                :src="thumb(r.playlist.coverUrl, 96)"
                alt=""
                class="ncm-recent__cover"
                loading="lazy"
              />
              <span v-else class="ncm-recent__cover ncm-recent__cover--blank" />
            </template>
          </VEntityField>
          <VEntityField
            max-width="100%"
            class="ncm-recent__info"
            :description="r.description"
          >
            <template #title>
              <div class="ncm-recent__title">
                <span class="ncm-recent__name" :title="r.name">{{ r.name }}</span>
                <VTag v-if="r.playlist.own" class="ncm-recent__own" theme="secondary">我创建的</VTag>
              </div>
            </template>
          </VEntityField>
        </template>
        <template #end>
          <VEntityField v-if="r.playedAt" :description="utils.date.format(r.playedAt)" />
          <VEntityField>
            <template #description>
              <a v-if="!r.playlist.privacy" :href="r.playlist.sitePath" target="_blank" rel="noopener" class="ncm-recent__link">在音乐页打开</a>
            </template>
          </VEntityField>
        </template>
      </VEntity>
    </VEntityContainer>
  </MusicCollectionPanel>
</template>

<style scoped>
.ncm-recent__cover {
  display: block;
  width: 48px;
  height: 48px;
  border-radius: 4px;
  object-fit: cover;
}
.ncm-recent__cover--blank {
  background: #f3f4f6;
}
.ncm-recent__info {
  flex: 1;
  min-width: 0;
}
.ncm-recent__title {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: .5rem;
}
.ncm-recent__name {
  display: -webkit-box;
  min-width: 0;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
  white-space: normal;
  font-size: .875rem;
  font-weight: 500;
}
.ncm-recent__own {
  flex: none;
}
.ncm-recent__link {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  padding: 0 .375rem;
  color: #2563eb;
  font-size: .8125rem;
  white-space: nowrap;
}
.ncm-recent__link:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-recent__link {
    min-height: 44px;
  }
}
</style>
