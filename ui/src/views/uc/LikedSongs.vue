<script setup lang="ts">
import { VEmpty } from '@halo-dev/components'
import { computed } from 'vue'
import MusicCollectionPanel from './MusicCollectionPanel.vue'
import SongList from './SongList.vue'
import SongPagination from './SongPagination.vue'
import { meApi } from './meApi'
import { useSongPagination } from './useSongPagination'

/** 喜欢的歌（红心歌曲），分页。取消红心后刷新当前页。 */
const { page, size, loading, error, data, panel, load } = useSongPagination(meApi.likes)

const items = computed(() => (data.value?.items ?? []).map((song) => ({ song })))

function onLikeChange(_songId: string, liked: boolean) {
  if (!liked) void load()
}
</script>

<template>
  <MusicCollectionPanel
    ref="panel"
    title="喜欢的歌"
    :summary="data && !error ? `${data.total} 首` : undefined"
    :refreshing="loading"
    :pending="loading && !data"
    :error="error"
    @refresh="load"
  >
    <VEmpty
      v-if="items.length === 0"
      title="还没有喜欢的歌"
      message="在音乐页或网易云音乐标为喜欢的歌曲会显示在这里。"
    />
    <template v-else>
      <SongList :items="items" :start-index="(data?.offset ?? 0) + 1" liked-by-default @like-change="onLikeChange" />
      <SongPagination
        v-model:page="page"
        v-model:size="size"
        :total="data?.total ?? 0"
        :loading="loading"
      />
    </template>
  </MusicCollectionPanel>
</template>
