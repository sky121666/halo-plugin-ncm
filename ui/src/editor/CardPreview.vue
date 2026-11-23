<script setup lang="ts">
import { computed } from 'vue'
import type { CardData } from './card-api'
import { TYPE_LABELS, normalizeType } from './link'

const props = withDefaults(
  defineProps<{
    data?: CardData | null
    /** 最多列出几首（歌单 / 专辑） */
    limit?: number
    compact?: boolean
  }>(),
  { data: null, limit: 3, compact: false },
)

const kind = computed(() => (props.data ? TYPE_LABELS[normalizeType(props.data.type)] : ''))
const cover = computed(() => {
  const c = props.data?.cover
  return c && /^https?:\/\//i.test(c) ? c : ''
})
const tracks = computed(() =>
  props.data && props.data.type !== 'song' ? props.data.tracks.slice(0, props.limit) : [],
)
const total = computed(() => props.data?.total ?? props.data?.tracks.length ?? 0)

function badge(a?: string) {
  if (a === 'trial') return '可能只能试听'
  if (a === 'unavailable') return '可能无法播放'
  return ''
}
</script>

<template>
  <div v-if="data" class="ncm-cp" :class="{ 'ncm-cp--compact': compact }">
    <div class="ncm-cp__head">
      <div class="ncm-cp__cover">
        <img v-if="cover" :src="cover" alt="" loading="lazy" referrerpolicy="no-referrer" />
      </div>
      <div class="ncm-cp__body">
        <div class="ncm-cp__top">
          <span class="ncm-cp__kind">{{ kind }}</span>
          <span class="ncm-cp__title" :title="data.title">{{ data.title || '（没有标题）' }}</span>
        </div>
        <div v-if="data.sub" class="ncm-cp__sub">{{ data.sub }}</div>
        <div v-if="data.type === 'song' && badge(data.tracks[0]?.availability)" class="ncm-cp__hint">
          {{ badge(data.tracks[0]?.availability) }}（以访客实际播放为准）
        </div>
        <div v-if="!data.tracks.length" class="ncm-cp__hint">没有可以播放的歌曲</div>
      </div>
      <span class="ncm-cp__play" aria-hidden="true">
        <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" /></svg>
      </span>
    </div>
    <ol v-if="tracks.length" class="ncm-cp__list">
      <li v-for="(t, i) in tracks" :key="t.id">
        <span class="ncm-cp__no">{{ i + 1 }}</span>
        <span class="ncm-cp__tname">{{ t.title }}</span>
        <span v-if="t.artist" class="ncm-cp__tartist">{{ t.artist }}</span>
        <span v-if="badge(t.availability)" class="ncm-cp__badge">{{ badge(t.availability) }}</span>
      </li>
      <li v-if="total > tracks.length" class="ncm-cp__more">共 {{ total }} 首</li>
    </ol>
  </div>
</template>

<style scoped>
.ncm-cp {
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafafa;
  color: #111827;
  font-size: 14px;
  line-height: 1.5;
}
.ncm-cp__head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ncm-cp__cover {
  flex: 0 0 64px;
  width: 64px;
  height: 64px;
  overflow: hidden;
  border-radius: 8px;
  background: #e5e7eb;
}
.ncm-cp--compact .ncm-cp__cover {
  flex-basis: 52px;
  width: 52px;
  height: 52px;
}
.ncm-cp__cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.ncm-cp__body {
  flex: 1;
  min-width: 0;
}
.ncm-cp__top {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.ncm-cp__kind {
  flex: none;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  color: #ec4141;
  border: 1px solid #ec4141;
  border-radius: 4px;
}
.ncm-cp__title {
  overflow: hidden;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ncm-cp__sub,
.ncm-cp__tartist,
.ncm-cp__more,
.ncm-cp__no {
  color: #6b7280;
}
.ncm-cp__sub {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ncm-cp__hint {
  font-size: 12px;
  color: #d97706;
}
.ncm-cp__play {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  color: #fff;
  background: #ec4141;
  border-radius: 50%;
}
.ncm-cp__play svg {
  width: 18px;
  height: 18px;
  fill: currentColor;
}
.ncm-cp__list {
  margin: 8px 0 0;
  padding: 6px 0 0;
  list-style: none;
  border-top: 1px solid #e5e7eb;
}
.ncm-cp__list li {
  display: flex;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ncm-cp__no {
  flex: none;
  width: 18px;
  text-align: right;
}
.ncm-cp__tname {
  overflow: hidden;
  text-overflow: ellipsis;
}
.ncm-cp__tartist {
  flex: none;
  font-size: 12px;
  line-height: 21px;
}
.ncm-cp__badge {
  flex: none;
  font-size: 12px;
  line-height: 21px;
  color: #d97706;
}
.ncm-cp__more {
  font-size: 12px;
}
</style>
