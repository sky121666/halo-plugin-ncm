import type { NcmCardType } from './link'

/** /music/card/{type}/{id} 的返回，字段为空时服务端不输出。 */
export interface CardTrack {
  id: string
  src: string
  title: string
  artist: string
  cover?: string
  availability?: 'full' | 'trial' | 'unavailable'
}

export interface CardData {
  type: NcmCardType
  id: string
  title: string
  sub?: string
  cover?: string
  path?: string
  total?: number
  tracks: CardTrack[]
}

export class CardError extends Error {
  readonly status: number
  readonly code: string | undefined

  constructor(status: number, code?: string) {
    super(cardErrorMessage(status, code))
    this.name = 'CardError'
    this.status = status
    this.code = code
  }
}

export function cardErrorMessage(status: number, code?: string): string {
  if (status === 404 || code === 'not_found') return '网易云音乐上找不到这个内容，请检查链接或 ID'
  if (status === 429 || code === 'rate_limited') return '请求太频繁，请稍等一会儿再试'
  if (status === 503 || code === 'unavailable') return '暂时连不上网易云音乐，请稍后再试'
  if (status === 0) return '网络出错，请检查网络后再试'
  return `预览失败（HTTP ${status}）`
}

const cache = new Map<string, Promise<CardData>>()

/** 取卡片数据。成功的结果在本页缓存，失败的不缓存。 */
export function fetchCard(type: NcmCardType, id: string): Promise<CardData> {
  const key = `${type}:${id}`
  const hit = cache.get(key)
  if (hit) return hit
  const p = (async () => {
    let r: Response
    try {
      r = await fetch(`/music/card/${type}/${id}`, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
    } catch {
      throw new CardError(0, undefined)
    }
    let body: Partial<CardData> & { error?: string } = {}
    try {
      body = await r.json()
    } catch {
      // 返回的不是 JSON，按状态码处理
    }
    if (!r.ok || !body || !body.id) {
      throw new CardError(r.ok ? 502 : r.status, body?.error)
    }
    return {
      ...body,
      title: body.title || '',
      tracks: Array.isArray(body.tracks) ? body.tracks : [],
    } as CardData
  })()
  cache.set(key, p)
  p.catch(() => cache.delete(key))
  return p
}
