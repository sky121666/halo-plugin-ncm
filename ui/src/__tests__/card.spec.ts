import { readFileSync } from 'node:fs'
import { URL as NodeURL } from 'node:url'
import { expect, it, vi } from 'vitest'

const script = readFileSync(new NodeURL('../../../src/main/resources/static/card.js', import.meta.url), 'utf8')

it('文章卡片生成 Folia 入口，并为关闭音乐页的站点保留网易云链接', async () => {
  document.body.innerHTML = `
    <div class="ncm-card" data-ncm-card data-type="playlist" data-id="21">
      <a href="https://music.163.com/#/playlist?id=21">歌单</a>
    </div>
    <div class="ncm-card" data-ncm-card data-type="song" data-id="24">
      <a href="https://music.163.com/#/song?id=24">单曲</a>
    </div>
  `
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
    ok: true,
    json: async () => url.endsWith('/playlist/21')
      ? { id: '21', path: '/music/playlist/21', tracks: [{ id: '23', title: '曲目' }] }
      : { id: '24', title: '单曲' },
  })))
  try {
    new Function(script)()
    if (document.readyState === 'loading') document.dispatchEvent(new Event('DOMContentLoaded'))
    await vi.waitFor(() => {
      expect(document.querySelector('[data-id="21"] a.ncm-pc__play')?.getAttribute('href')).toBe('/music/playlist/21')
      expect(document.querySelector('[data-id="21"] a.ncm-pc__tplay')?.getAttribute('href')).toBe('/music/song/23')
      expect(document.querySelector('[data-id="24"] a.ncm-pc__play')?.getAttribute('href'))
        .toBe('https://music.163.com/#/song?id=24')
    })
  } finally {
    vi.unstubAllGlobals()
  }
})
