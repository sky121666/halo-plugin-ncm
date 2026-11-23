import type { NcmFeaturedItem, NcmFeaturedStats } from '@/api/generated'

export type Estimate = 'full' | 'trial' | 'unavailable' | 'pending'

export const ESTIMATE_LABEL: Record<Estimate, string> = {
  full: '完整',
  trial: '试听',
  unavailable: '不可播',
  pending: '待确认',
}

export const ESTIMATE_THEME: Record<Estimate, 'default' | 'primary' | 'secondary' | 'danger'> = {
  full: 'primary',
  trial: 'secondary',
  unavailable: 'danger',
  pending: 'default',
}

export function asEstimate(v: unknown): Estimate {
  return v === 'full' || v === 'trial' || v === 'unavailable' ? v : 'pending'
}

/** 表查找都放在函数里，模板只调用函数（避免在模板里对常量表做下标访问引起的类型问题）。 */
export function estimateLabel(v: unknown): string {
  return ESTIMATE_LABEL[asEstimate(v)]
}

export function estimateTheme(v: unknown): 'default' | 'primary' | 'secondary' | 'danger' {
  return ESTIMATE_THEME[asEstimate(v)]
}

export function previewStateText(state: unknown): string {
  switch (state) {
    case 'ok':
      return '游客可以公开访问'
    case 'private':
      return '私密歌单或访客无权访问，不能发布'
    case 'missing':
      return '歌单不存在或已被删除'
    default:
      return '这次没能确认是否公开（超时、限流或出错），只能先保存草稿'
  }
}

export function previewDotState(state: unknown): 'success' | 'warning' | 'error' {
  if (state === 'ok') return 'success'
  if (state === 'private' || state === 'missing') return 'error'
  return 'warning'
}

/** 试听按钮文字：只有正在播放这一首（没有暂停）时显示“暂停”。 */
export function playButtonText(trackId: string, playingId: string, paused: boolean): string {
  return playingId === trackId && !paused ? '暂停' : '用当前账号试听'
}

/** “完整 32 · 试听 11 · 不可播 7 · 待确认 0”，统计不全时注明。 */
export function statsSummary(s?: NcmFeaturedStats | null): string {
  if (!s) return '—'
  const parts = [
    `完整 ${s.full}`,
    `试听 ${s.trial}`,
    `不可播 ${s.unavailable}`,
    `待确认 ${s.pending}`,
  ]
  const tail = s.truncated ? `（共 ${s.total} 首，只统计了前 ${s.counted} 首）` : ''
  return parts.join(' · ') + tail
}

/** 列表里的状态点。 */
export function stateInfo(item: Pick<NcmFeaturedItem, 'published' | 'state' | 'lastError'>): {
  state: 'success' | 'warning' | 'error' | 'default'
  text: string
} {
  if (item.state === 'missing') return { state: 'error', text: '已失效：歌单不存在或已删除' }
  if (item.state === 'private') return { state: 'error', text: '私密或访客无权访问，访客看不到' }
  if (item.state === 'error' || item.state === 'unchecked') {
    return {
      state: 'warning',
      text: item.published ? '已发布 · 待确认（核对失败）' : '草稿 · 待确认',
    }
  }
  return item.published ? { state: 'success', text: '已发布' } : { state: 'default', text: '草稿' }
}

/**
 * 把 name 上移（delta = -1）或下移（delta = 1）一位，返回新顺序；已经在最前 / 最后或找不到时返回 null。
 */
export function moveName(names: readonly string[], name: string, delta: -1 | 1): string[] | null {
  const i = names.indexOf(name)
  const j = i + delta
  if (i < 0 || j < 0 || j >= names.length) return null
  const next = names.slice()
  next[i] = names[j] as string
  next[j] = name
  return next
}

/** 从 Halo / axios 的错误里取出给人看的说明。 */
export function errorText(e: unknown, fallback = '操作失败'): string {
  const err = e as {
    response?: { data?: { detail?: string; message?: string; title?: string } | string }
    message?: string
  }
  const data = err?.response?.data
  if (typeof data === 'string' && data.trim()) return data
  if (data && typeof data === 'object') {
    return data.detail || data.message || data.title || fallback
  }
  return err?.message || fallback
}
