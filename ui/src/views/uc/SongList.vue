<script setup lang="ts">
import {
  Toast,
  VAlert,
  VButton,
  VEntity,
  VEntityContainer,
  VEntityField,
  VTag,
} from '@halo-dev/components'
import { inject, nextTick, reactive, ref, shallowRef, watch } from 'vue'
import RiHeartFill from '~icons/ri/heart-fill'
import RiHeartLine from '~icons/ri/heart-line'
import AddToPlaylistModal from './AddToPlaylistModal.vue'
import MusicRowMenu from './MusicRowMenu.vue'
import { libraryProblem, openAccountBinding, type LibraryProblem } from '@/composables/libraryRead'
import { MAX_CHECK_IDS, meApi, playbackPath, thumb, type MeSong, type SongItem } from './meApi'

/**
 * 歌曲列表：序号、封面、歌名、歌手 · 专辑、时长、红心按钮、音乐页播放。
 * 更多菜单负责加入歌单，并在可编辑的歌单中提供移除操作。
 */

const props = withDefaults(
  defineProps<{
    items: SongItem[]
    /** 第一首的序号 */
    startIndex?: number
    /** 是否显示红心按钮（会批量查询红心状态） */
    likable?: boolean
    likedByDefault?: boolean
    removable?: boolean
  }>(),
  { startIndex: 1, likable: true },
)
const emit = defineEmits<{
  (e: 'likeChange', songId: string, liked: boolean): void
  (e: 'added', playlistId: string): void
  (e: 'remove', song: MeSong, trigger: HTMLButtonElement): void
}>()

/** 歌曲 ID → 是否已红心；没查到时不显示红心按钮 */
const liked = reactive<Record<string, boolean>>({})
const pending = reactive<Record<string, boolean>>({})
const adding = ref<{ ids: string[]; trigger: HTMLButtonElement }>()
const likeReadError = shallowRef<LibraryProblem>()
const likeRetry = ref(0)
const rows = ref<InstanceType<typeof VEntityContainer>>()
const openBinding = inject(openAccountBinding)

function songActions(song: MeSong) {
  return [
    {
      label: '加入歌单',
      onSelect: (trigger: HTMLButtonElement) => { adding.value = { ids: [song.id], trigger } },
    },
    ...(props.removable
      ? [{
          label: '从歌单移除',
          danger: true,
          onSelect: (trigger: HTMLButtonElement) => emit('remove', song, trigger),
        }]
      : []),
  ]
}

async function closeAdd() {
  const trigger = adding.value?.trigger
  adding.value = undefined
  await nextTick()
  trigger?.focus()
}

function retryLikes() {
  const list = rows.value?.$el as HTMLElement | undefined
  list?.querySelector<HTMLAnchorElement>('.ncm-song__name')?.focus()
  likeRetry.value++
}

watch([() => props.items, likeRetry], async (_items, _previous, onCleanup) => {
  likeReadError.value = undefined
  if (!props.likable) return
  const ids = [...new Set(props.items.map((i) => i.song.id))].filter((id) => !(id in liked))
  if (props.likedByDefault) {
    ids.forEach((id) => { liked[id] = true })
    return
  }
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  for (let i = 0; i < ids.length; i += MAX_CHECK_IDS) {
    try {
      const r = await meApi.checkLikes(ids.slice(i, i + MAX_CHECK_IDS), controller.signal)
      if (controller.signal.aborted) return
      for (const [id, value] of Object.entries(r.liked)) {
        if (!(id in liked)) liked[id] = value
      }
    } catch (cause) {
      if (!controller.signal.aborted) likeReadError.value = libraryProblem(cause, '暂时无法读取喜欢状态。')
      return
    }
  }
}, { immediate: true })

async function toggle(song: MeSong) {
  if (pending[song.id]) return
  const next = !liked[song.id]
  pending[song.id] = true
  try {
    const r = await meApi.like(song.id, next)
    liked[song.id] = r.liked
    Toast.success(r.liked ? '已加入「喜欢的歌」' : '已取消喜欢')
    emit('likeChange', song.id, r.liked)
  } catch {
    // 出错提示由 Halo 的请求拦截器统一弹出
  } finally {
    pending[song.id] = false
  }
}

function subtitle(song: MeSong): string {
  return [song.artistNames, song.album?.name].filter((s) => !!s).join(' · ')
}
</script>

<template>
  <VAlert
    v-if="likeReadError"
    class="m-4 ncm-song__like-error"
    type="warning"
    title="喜欢状态读取失败"
    :description="likeReadError.message"
    :closable="false"
  >
    <template #actions>
      <VButton v-if="likeReadError.recovery === 'retry'" size="sm" @click="retryLikes">重试</VButton>
      <VButton v-else-if="likeReadError.recovery === 'account'" size="sm" @click="openBinding?.()">
        查看账号绑定
      </VButton>
    </template>
  </VAlert>
  <VEntityContainer ref="rows">
    <VEntity v-for="(item, i) in items" :key="item.key ?? `${item.song.id}-${i}`">
      <template #start>
        <VEntityField>
          <template #description>
            <span class="ncm-song__index">{{ startIndex + i }}</span>
          </template>
        </VEntityField>
        <VEntityField>
          <template #description>
            <img
              v-if="item.song.coverUrl"
              :src="thumb(item.song.coverUrl, 80)"
              alt=""
              class="ncm-song__cover"
              loading="lazy"
            />
            <span v-else class="ncm-song__cover ncm-song__cover--blank" />
          </template>
        </VEntityField>
        <VEntityField :description="subtitle(item.song)">
          <template #title>
            <a :href="item.song.sitePath" target="_blank" rel="noopener" class="ncm-song__name">
              {{ item.song.name || `歌曲 ${item.song.id}` }}
            </a>
          </template>
          <template #extra>
            <VTag v-if="item.song.vip" theme="primary">VIP</VTag>
            <VTag v-if="item.song.playable === false">可能无法播放</VTag>
          </template>
        </VEntityField>
      </template>
      <template #end>
        <div class="ncm-song__end">
          <div v-if="item.meta || item.song.durationText" class="ncm-song__meta">
            <span v-if="item.meta">{{ item.meta }}</span>
            <span v-if="item.song.durationText">{{ item.song.durationText }}</span>
          </div>
          <div class="ncm-song__actions">
            <VButton
              v-if="likable && item.song.id in liked"
              size="sm"
              ghost
              class="ncm-song__like"
              :loading="pending[item.song.id]"
              :title="liked[item.song.id] ? '取消喜欢' : '喜欢'"
              :aria-label="`喜欢「${item.song.name || item.song.id}」`"
              :aria-pressed="liked[item.song.id]"
              @click="toggle(item.song)"
            >
              <template #icon>
                <RiHeartFill v-if="liked[item.song.id]" class="ncm-song__heart" />
                <RiHeartLine v-else />
              </template>
            </VButton>
            <a
              :href="playbackPath(item.song.sitePath)"
              target="_blank"
              rel="noopener"
              :aria-label="`在音乐页播放「${item.song.name || item.song.id}」`"
              title="在音乐页播放"
              class="ncm-song__link"
            >播放</a>
            <MusicRowMenu
              :label="`歌曲「${item.song.name || item.song.id}」的更多操作`"
              :actions="songActions(item.song)"
            />
          </div>
        </div>
      </template>
    </VEntity>
  </VEntityContainer>

  <AddToPlaylistModal v-if="adding" :song-ids="adding.ids" @close="closeAdd" @added="emit('added', $event.id)" />
</template>

<style scoped>
.ncm-song__index {
  display: inline-block;
  min-width: 1.75rem;
  color: #9ca3af;
  font-size: 12px;
  text-align: right;
}
.ncm-song__cover {
  display: block;
  width: 40px;
  height: 40px;
  border-radius: 4px;
  object-fit: cover;
}
.ncm-song__cover--blank {
  background: #f3f4f6;
}
.ncm-song__name {
  color: inherit;
  overflow-wrap: anywhere;
}
.ncm-song__name:hover {
  text-decoration: underline;
}
.ncm-song__end,
.ncm-song__meta,
.ncm-song__actions {
  display: flex;
  align-items: center;
  gap: .5rem;
}
.ncm-song__meta {
  flex-wrap: wrap;
  color: #4b5563;
  font-size: .75rem;
}
.ncm-song__actions {
  margin-left: auto;
}
.ncm-song__link {
  display: inline-flex;
  min-height: 32px;
  min-width: 44px;
  align-items: center;
  justify-content: center;
  padding: 0 .625rem;
  border: 1px solid #d1d5db;
  border-radius: .375rem;
  color: #1f2937;
  font-size: .8125rem;
}
.ncm-song__link:hover {
  background: #f3f4f6;
}
.ncm-song__link:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.ncm-song__heart {
  color: #ef4444;
}
@media (max-width: 640px) {
  .ncm-song__end {
    width: 100%;
    flex-wrap: wrap;
  }
}
@media (max-width: 640px), (pointer: coarse) {
  .ncm-song__link,
  .ncm-song__like,
  .ncm-song__like-error :deep(button) {
    min-width: 44px;
    min-height: 44px;
  }
}
</style>
