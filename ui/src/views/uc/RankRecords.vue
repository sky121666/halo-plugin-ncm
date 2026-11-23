<script setup lang="ts">
import { VEmpty } from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import MusicTabs from '@/components/MusicTabs.vue'
import MusicCollectionPanel from './MusicCollectionPanel.vue'
import SongList from './SongList.vue'
import { meApi } from './meApi'
import { useLibraryRead } from '@/composables/libraryRead'

/** 听歌排行：最近一周 / 所有时间。 */
const TABS = [
  { id: 'week', label: '最近一周' },
  { id: 'all', label: '所有时间' },
]
const type = ref<string>('week')
const { data, loading, error, load } = useLibraryRead((signal) =>
  meApi.rank(type.value === 'all' ? 'all' : 'week', signal),
)

const items = computed(() =>
  (data.value?.items ?? []).map((r) => ({
    song: r.song,
    meta: r.playCount ? `播放 ${r.playCount} 次` : undefined,
  })),
)

watch(type, () => {
  data.value = undefined
  void load()
})
onMounted(load)
</script>

<template>
  <MusicCollectionPanel
    title="听歌排行"
    :refreshing="loading"
    :pending="loading && !data"
    :error="error"
    @refresh="load"
  >
    <template #toolbar>
      <MusicTabs v-model="type" label="排行时间" :items="TABS" />
    </template>
    <VEmpty
      v-if="items.length === 0"
      title="暂无听歌排行"
      :message="type === 'week' ? '最近一周暂无听歌记录。' : '网易云账号暂无听歌记录。'"
    />
    <SongList v-else :items="items" />
  </MusicCollectionPanel>
</template>
