import { describe, expect, it } from 'vitest'
import {
  asEstimate,
  errorText,
  estimateLabel,
  estimateTheme,
  moveName,
  playButtonText,
  previewDotState,
  previewStateText,
  stateInfo,
  statsSummary,
} from '../featured'

describe('moveName', () => {
  const names = ['playlist-1', 'playlist-2', 'playlist-3']

  it('上移和下移', () => {
    expect(moveName(names, 'playlist-2', -1)).toEqual(['playlist-2', 'playlist-1', 'playlist-3'])
    expect(moveName(names, 'playlist-2', 1)).toEqual(['playlist-1', 'playlist-3', 'playlist-2'])
  })

  it('到头时返回 null，并且不修改原数组', () => {
    expect(moveName(names, 'playlist-1', -1)).toBeNull()
    expect(moveName(names, 'playlist-3', 1)).toBeNull()
    expect(moveName(names, 'playlist-9', 1)).toBeNull()
    expect(names).toEqual(['playlist-1', 'playlist-2', 'playlist-3'])
  })
})

describe('stateInfo', () => {
  it('草稿、已发布、失效、待确认', () => {
    expect(stateInfo({ published: false, state: 'ok' }).text).toBe('草稿')
    expect(stateInfo({ published: true, state: 'ok' }).state).toBe('success')
    expect(stateInfo({ published: true, state: 'missing' }).state).toBe('error')
    expect(stateInfo({ published: true, state: 'private' }).state).toBe('error')
    expect(stateInfo({ published: true, state: 'error' }).text).toContain('待确认')
    expect(stateInfo({ published: false, state: 'unchecked' }).state).toBe('warning')
  })
})

describe('statsSummary', () => {
  it('四类数量，统计不全时注明', () => {
    const s = { full: 3, trial: 2, unavailable: 1, pending: 0, counted: 6, total: 6, truncated: false }
    expect(statsSummary(s)).toBe('完整 3 · 试听 2 · 不可播 1 · 待确认 0')
    expect(statsSummary({ ...s, counted: 1000, total: 1200, truncated: true })).toContain('只统计了前 1000 首')
    expect(statsSummary(null)).toBe('—')
  })
})

describe('asEstimate / errorText', () => {
  it('未知值算待确认', () => {
    expect(asEstimate('full')).toBe('full')
    expect(asEstimate(undefined)).toBe('pending')
    expect(asEstimate('weird')).toBe('pending')
  })

  it('优先用后端的 detail', () => {
    expect(errorText({ response: { data: { detail: '这是私密歌单' } } })).toBe('这是私密歌单')
    expect(errorText({ message: 'Network Error' })).toBe('Network Error')
    expect(errorText({}, '失败')).toBe('失败')
  })
})

describe('模板用的查表函数', () => {
  it('预估标签和颜色，未知值算待确认', () => {
    expect(estimateLabel('trial')).toBe('试听')
    expect(estimateLabel(undefined)).toBe('待确认')
    expect(estimateTheme('unavailable')).toBe('danger')
    expect(estimateTheme('???')).toBe('default')
  })

  it('预览状态：只有 ok 是可公开，超时限流算待确认', () => {
    expect(previewDotState('ok')).toBe('success')
    expect(previewDotState('private')).toBe('error')
    expect(previewDotState('error')).toBe('warning')
    expect(previewStateText('missing')).toContain('删除')
    expect(previewStateText('error')).toContain('草稿')
  })
})

describe('playButtonText', () => {
  it('只有正在播放且没暂停时显示“暂停”', () => {
    expect(playButtonText('1', '1', false)).toBe('暂停')
    expect(playButtonText('1', '1', true)).toBe('用当前账号试听')
    expect(playButtonText('1', '2', false)).toBe('用当前账号试听')
    expect(playButtonText('1', '', true)).toBe('用当前账号试听')
  })
})
