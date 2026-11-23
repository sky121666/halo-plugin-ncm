import { describe, expect, it } from 'vitest'
import { canonicalUrl, cardHtml, defaultTitle, parseNcmInput, parseNcmUrl } from '../link'

function ok(input: string, idType?: 'song' | 'playlist' | 'album') {
  const r = parseNcmInput(input, idType)
  return r.ok ? `${r.type}:${r.id}` : `x:${r.reason}`
}

describe('parseNcmInput', () => {
  it('网页版长链接', () => {
    expect(ok('https://music.163.com/#/song?id=186016')).toBe('song:186016')
    expect(ok('https://music.163.com/song?id=186016&userid=1')).toBe('song:186016')
    expect(ok('https://music.163.com/#/song?userid=1&id=5')).toBe('song:5')
    expect(ok('https://music.163.com/#/playlist?id=24381616')).toBe('playlist:24381616')
    expect(ok('https://music.163.com/#/my/m/music/playlist?id=24381616')).toBe('playlist:24381616')
    expect(ok('https://music.163.com/#/discover/toplist?id=3778678')).toBe('playlist:3778678')
    expect(ok('https://music.163.com/#/album?id=18915')).toBe('album:18915')
    expect(ok('https://music.163.com/#/song?id=1&amp;userid=2')).toBe('song:1')
  })

  it('手机版链接', () => {
    expect(ok('https://y.music.163.com/m/song?id=186016&userid=1')).toBe('song:186016')
    expect(ok('https://y.music.163.com/m/song?app_version=8&id=186016')).toBe('song:186016')
    expect(ok('https://y.music.163.com/m/song/186016/')).toBe('song:186016')
    expect(ok('https://y.music.163.com/m/playlist?id=123')).toBe('playlist:123')
    expect(ok('https://music.163.com/m/album?id=3')).toBe('album:3')
  })

  it('分享文案和没有协议头的链接', () => {
    expect(
      ok('分享周杰伦的单曲《晴天》: https://y.music.163.com/m/song?id=186016&userid=1&dlt=0846 (来自@网易云音乐)'),
    ).toBe('song:186016')
    expect(ok('分享歌单：https://music.163.com/playlist?id=24381616&userid=1。')).toBe('playlist:24381616')
    expect(ok('music.163.com/#/album?id=18915')).toBe('album:18915')
  })

  it('纯数字 ID 用选择的类型', () => {
    expect(ok(' 186016 ')).toBe('song:186016')
    expect(ok('123', 'playlist')).toBe('playlist:123')
    expect(ok('123', 'album')).toBe('album:123')
    expect(ok('123456789012345678901')).toBe('x:invalid')
  })

  it('短链接、其他类型和无效内容', () => {
    expect(ok('http://163cn.tv/zHk2YzO')).toBe('x:short-link')
    expect(ok('分享 https://163cn.tv/abc (来自@网易云音乐)')).toBe('x:short-link')
    expect(ok('https://music.163.com/#/artist?id=6452')).toBe('x:unsupported')
    expect(ok('https://music.163.com/#/songlist?id=1')).toBe('x:invalid')
    expect(ok('https://music.163.com.evil.com/song?id=1')).toBe('x:invalid')
    expect(ok('hello')).toBe('x:invalid')
    expect(ok('')).toBe('x:empty')
    expect(ok('   ')).toBe('x:empty')
  })

  it('不认识的结果带提示文字', () => {
    const r = parseNcmInput('http://163cn.tv/abc')
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.message).toContain('长链接')
  })
})

describe('parseNcmUrl', () => {
  it('只接受网易云域名', () => {
    expect(parseNcmUrl('https://example.com/song?id=1')).toBeNull()
    expect(parseNcmUrl('https://st.music.163.com/song/12/?x')).toEqual({ type: 'song', id: '12' })
  })
})

describe('存进文章的 HTML', () => {
  it('普通链接，不依赖插件', () => {
    expect(canonicalUrl('song', '186016')).toBe('https://music.163.com/#/song?id=186016')
    expect(cardHtml('playlist', '24381616', '我喜欢的<音乐>')).toBe(
      '<div class="ncm-card" data-ncm-card="" data-type="playlist" data-id="24381616">' +
        '<a href="https://music.163.com/#/playlist?id=24381616">我喜欢的&lt;音乐&gt;</a></div>',
    )
    expect(cardHtml('album', '1', ' ')).toContain(`>${defaultTitle('album', '1')}</a>`)
  })
})
