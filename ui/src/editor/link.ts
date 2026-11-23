/**
 * 网易云链接解析（纯函数，没有副作用，方便单元测试）。
 *
 * 支持：
 * - music.163.com 的长链接：https://music.163.com/#/song?id=1、/song?id=1、/#/playlist?id=1、/#/my/m/music/playlist?id=1、
 *   /#/album?id=1、/#/discover/toplist?id=1（榜单按歌单处理）
 * - 手机网页：https://y.music.163.com/m/song?id=1、/m/song/1、/m/playlist?id=1 等
 * - 分享文案：“分享周杰伦的单曲《晴天》: https://y.music.163.com/m/song?id=186016&userid=1 (来自@网易云音乐)”
 * - 没有协议头的链接：music.163.com/#/song?id=1
 * - 纯数字 ID，类型由调用方给（默认歌曲）
 * 不支持：163cn.tv 短链接（需要访问网络才能展开，前端不做，提示用户粘贴长链接）；歌手、MV、电台等其他类型。
 */

export type NcmCardType = 'song' | 'playlist' | 'album'

export const CARD_TYPES: readonly NcmCardType[] = ['song', 'playlist', 'album']

export const TYPE_LABELS: Record<NcmCardType, string> = {
  song: '歌曲',
  playlist: '歌单',
  album: '专辑',
}

export type ParseResult =
  | { ok: true; type: NcmCardType; id: string; from: 'link' | 'id' }
  | { ok: false; reason: 'empty' | 'short-link' | 'unsupported' | 'invalid'; message: string }

const ID_RE = /^\d{1,20}$/

/** 文本里的网易云地址（可以没有协议头）。遇到空白、引号、尖括号和常见中文标点就结束。 */
const NCM_URL_RE =
  /(?:https?:\/\/)?(?:[a-z0-9-]+\.)*(?:music\.163\.com|163cn\.tv)(?:[/?#][^\s"'<>，。！？、；（）()【】《》]*)?/gi

const OTHER_TYPES_RE = /(?:^|[/#])(?:m\/)?(artist|mv|video|dj|djradio|program|user|event|radio)(?:[/?]|$)/i

export function isCardType(v: unknown): v is NcmCardType {
  return typeof v === 'string' && (CARD_TYPES as readonly string[]).includes(v)
}

export function normalizeType(v: unknown): NcmCardType {
  return isCardType(v) ? v : 'song'
}

export function isValidId(v: unknown): v is string {
  return typeof v === 'string' && ID_RE.test(v)
}

/** 解析单个网易云地址；不是网易云的歌曲 / 歌单 / 专辑地址时返回 null。 */
export function parseNcmUrl(raw: string): { type: NcmCardType; id: string } | null {
  const m = /^(?:https?:\/\/)?((?:[a-z0-9-]+\.)*music\.163\.com)(.*)$/i.exec(raw.trim())
  if (!m) return null
  // 路径、查询和 # 后面的部分放在一起看：网页版的参数在 # 后面，手机版在 ? 后面
  const rest = (m[2] || '').replace(/&amp;/g, '&')
  const typeMatch = /(?:^|[/#])(?:m\/)?(?:my\/m\/music\/|discover\/)?(song|playlist|album|toplist)(?:\/(\d{1,20}))?(?=[/?#&]|$)/i.exec(
    rest,
  )
  if (!typeMatch) return null
  const word = (typeMatch[1] || '').toLowerCase()
  const type: NcmCardType = word === 'toplist' ? 'playlist' : (word as NcmCardType)
  let id = typeMatch[2]
  if (!id) {
    const q = /[?&#]id=(\d{1,20})(?![\d])/i.exec(rest.slice(typeMatch.index))
    id = q ? q[1] : undefined
  }
  if (!id || !isValidId(id)) return null
  return { type, id }
}

/**
 * 解析用户粘贴的内容。
 * @param input 链接、分享文案或数字 ID
 * @param idType 只填数字时使用的类型
 */
export function parseNcmInput(input: string | null | undefined, idType: NcmCardType = 'song'): ParseResult {
  const text = (input || '').trim()
  if (!text) {
    return { ok: false, reason: 'empty', message: '请粘贴网易云音乐的链接或数字 ID' }
  }
  if (ID_RE.test(text)) {
    return { ok: true, type: normalizeType(idType), id: text, from: 'id' }
  }
  const urls = text.match(NCM_URL_RE) || []
  let sawShort = false
  let sawOther = false
  for (const u of urls) {
    if (/163cn\.tv/i.test(u)) {
      sawShort = true
      continue
    }
    const r = parseNcmUrl(u)
    if (r) return { ok: true, type: r.type, id: r.id, from: 'link' }
    if (OTHER_TYPES_RE.test(u.replace(/^(?:https?:\/\/)?[^/?#]+/i, ''))) sawOther = true
  }
  if (sawShort) {
    return {
      ok: false,
      reason: 'short-link',
      message: '暂不支持 163cn.tv 短链接。请在浏览器里打开它，再复制地址栏里 music.163.com 开头的长链接粘贴进来。',
    }
  }
  if (sawOther) {
    return { ok: false, reason: 'unsupported', message: '目前只支持歌曲、歌单和专辑' }
  }
  return {
    ok: false,
    reason: 'invalid',
    message: '没有识别出网易云音乐的歌曲、歌单或专辑。请粘贴 music.163.com 的链接，或者填数字 ID 并选择类型。',
  }
}

/** 存进文章的链接：没有插件时也能直接打开网易云。 */
export function canonicalUrl(type: NcmCardType, id: string): string {
  return `https://music.163.com/#/${type}?id=${id}`
}

/** 拿不到标题时用的默认文字。 */
export function defaultTitle(type: NcmCardType, id: string): string {
  return `网易云音乐${TYPE_LABELS[normalizeType(type)]} ${id}`
}

/**
 * 存进文章的 HTML（和编辑器节点 renderHTML 的输出一致，这里给测试和文档用）：
 * <div class="ncm-card" data-ncm-card="" data-type="song" data-id="1"><a href="https://music.163.com/#/song?id=1">标题</a></div>
 */
export function cardHtml(type: NcmCardType, id: string, title: string): string {
  const esc = (s: string) =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  const t = normalizeType(type)
  const label = title.trim() || defaultTitle(t, id)
  return `<div class="ncm-card" data-ncm-card="" data-type="${t}" data-id="${esc(id)}"><a href="${esc(
    canonicalUrl(t, id),
  )}">${esc(label)}</a></div>`
}
