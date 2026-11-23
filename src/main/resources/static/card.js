/*
 * 网易云音乐插件：文章插歌卡片。
 *
 * 文章里存的是普通链接，没有 JS、在 RSS 里或插件被卸载时照样能看：
 *   <div class="ncm-card" data-ncm-card data-type="song|playlist|album" data-id="数字ID">
 *     <a href="https://music.163.com/#/song?id=...">标题</a>
 *   </div>
 * 卡片显示封面和曲目预览，播放链接进入 Folia。
 * 加载失败时保留原有链接。
 */
;(function () {
  'use strict'
  if (window.__ncmCard) return
  window.__ncmCard = true

  var me = document.currentScript || document.querySelector('script[data-ncm-card-js]')
  var ENDPOINT = (me && me.getAttribute('data-endpoint')) || '/music/card/'
  var KIND = { song: '单曲', playlist: '歌单', album: '专辑' }
  var ID_RE = /^\d{1,20}$/
  var PREVIEW = 5
  var ICON_PLAY = 'M8 5v14l11-7z'
  var SVG_NS = 'http://www.w3.org/2000/svg'

  // ---------- 数据 ----------

  var cache = {}

  function fetchCard(type, id) {
    var key = type + ':' + id
    if (!cache[key]) {
      var p = fetch(ENDPOINT + type + '/' + id, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      }).then(function (r) {
        return r
          .json()
          .catch(function () {
            return null
          })
          .then(function (body) {
            if (r.ok && body && body.id) return body
            var err = new Error('card ' + r.status)
            err.status = r.status
            err.code = body && body.error
            throw err
          })
      })
      cache[key] = p
      // 不存在的结果留在缓存里，其他失败下次重新请求
      p.catch(function (e) {
        if (!e || e.status !== 404) delete cache[key]
      })
    }
    return cache[key]
  }

  // ---------- DOM 小工具（只用 textContent / setAttribute，不拼接 HTML） ----------

  function h(tag, cls, text) {
    var el = document.createElement(tag)
    if (cls) el.className = cls
    if (text != null && text !== '') el.textContent = String(text)
    return el
  }

  function icon(d) {
    var svg = document.createElementNS(SVG_NS, 'svg')
    svg.setAttribute('viewBox', '0 0 24 24')
    svg.setAttribute('aria-hidden', 'true')
    var path = document.createElementNS(SVG_NS, 'path')
    path.setAttribute('d', d)
    svg.appendChild(path)
    return svg
  }

  function httpUrl(u) {
    return typeof u === 'string' && /^https?:\/\//i.test(u) ? u : ''
  }

  function sitePath(u) {
    return typeof u === 'string' && /^\/music\/(song|album|playlist)\/\d{1,20}$/.test(u) ? u : ''
  }

  function clear(el) {
    while (el.firstChild) el.removeChild(el.firstChild)
  }

  // ---------- 渲染 ----------

  function process(el) {
    if (el.__ncmCard) return
    var type = el.getAttribute('data-type')
    var id = (el.getAttribute('data-id') || '').trim()
    if (!KIND.hasOwnProperty(type) || !ID_RE.test(id)) return
    var a = el.querySelector('a[href]')
    el.__ncmCard = {
      type: type,
      id: id,
      href: a ? a.getAttribute('href') : '',
      label: a ? (a.textContent || '').trim() : '',
      fallback: Array.prototype.slice.call(el.childNodes),
    }
    el.setAttribute('data-ncm-card-state', 'loading')
    el.classList.add('is-loading')
    fetchCard(type, id).then(
      function (d) {
        if (!el.__ncmCard) return
        render(el, d)
      },
      function (e) {
        if (!el.__ncmCard) return
        fail(el, e)
      },
    )
  }

  function render(el, d) {
    var info = el.__ncmCard
    var tracks = Array.isArray(d.tracks)
      ? d.tracks.filter(function (t) {
          return !!t && ID_RE.test(String(t.id))
        })
      : []
    var title = d.title || info.label || KIND[info.type]
    var link = sitePath(d.path) || httpUrl(info.href) || 'https://music.163.com/#/' + info.type + '?id=' + info.id

    var box = h('div', 'ncm-pc')
    var head = h('div', 'ncm-pc__head')

    var coverWrap = h('div', 'ncm-pc__cover')
    var cover = httpUrl(d.cover)
    if (cover) {
      var img = h('img')
      img.src = cover
      img.alt = ''
      img.loading = 'lazy'
      img.decoding = 'async'
      coverWrap.appendChild(img)
    }
    head.appendChild(coverWrap)

    var body = h('div', 'ncm-pc__body')
    var top = h('div', 'ncm-pc__top')
    top.appendChild(h('span', 'ncm-pc__kind', KIND[info.type]))
    var name
    if (link) {
      name = h('a', 'ncm-pc__title', title)
      name.href = link
      if (!sitePath(link)) {
        name.target = '_blank'
        name.rel = 'noopener noreferrer'
      }
    } else {
      name = h('span', 'ncm-pc__title', title)
    }
    top.appendChild(name)
    body.appendChild(top)
    if (d.sub) body.appendChild(h('div', 'ncm-pc__sub', d.sub))
    head.appendChild(body)
    var play = playLink(link, 'ncm-pc__play', title)
    play.appendChild(icon(ICON_PLAY))
    head.appendChild(play)
    box.appendChild(head)

    if (info.type !== 'song' && tracks.length) {
      var list = h('ol', 'ncm-pc__list')
      tracks.slice(0, PREVIEW).forEach(function (t, i) {
        var li = h('li', 'ncm-pc__track')
        li.setAttribute('data-id', String(t.id))
        var path = sitePath(d.path) ? '/music/song/' + t.id : 'https://music.163.com/#/song?id=' + t.id
        var btn = playLink(path, 'ncm-pc__tplay', t.title || '歌曲')
        btn.appendChild(h('span', 'ncm-pc__no', i + 1))
        btn.appendChild(icon(ICON_PLAY))
        li.appendChild(btn)
        var main = h('span', 'ncm-pc__tmain')
        main.appendChild(h('span', 'ncm-pc__tname', t.title || ''))
        if (t.artist) main.appendChild(h('span', 'ncm-pc__tartist', t.artist))
        li.appendChild(main)
        var badge = availabilityBadge(t.availability)
        if (badge) li.appendChild(badge)
        list.appendChild(li)
      })
      box.appendChild(list)
      var total = typeof d.total === 'number' && d.total > 0 ? d.total : tracks.length
      if (total > PREVIEW || link) {
        var foot = h('div', 'ncm-pc__foot')
        foot.appendChild(h('span', '', '共 ' + total + ' 首'))
        if (link) {
          var more = h('a', 'ncm-pc__more', sitePath(link) ? '查看全部' : '在网易云音乐查看')
          more.href = link
          if (!sitePath(link)) {
            more.target = '_blank'
            more.rel = 'noopener noreferrer'
          }
          foot.appendChild(more)
        }
        box.appendChild(foot)
      }
    }

    clear(el)
    el.appendChild(box)
    el.classList.remove('is-loading', 'is-error', 'is-missing')
    el.classList.add('is-ready', 'is-' + info.type)
    el.setAttribute('data-ncm-card-state', 'ready')
    var badge = info.type === 'song' && tracks[0] && availabilityBadge(tracks[0].availability)
    if (badge) top.appendChild(badge)
  }

  function restoreFallback(el) {
    var info = el.__ncmCard
    clear(el)
    info.fallback.forEach(function (n) {
      el.appendChild(n)
    })
  }

  function fail(el, e) {
    var missing = e && e.status === 404
    restoreFallback(el)
    el.classList.remove('is-loading')
    el.classList.add(missing ? 'is-missing' : 'is-error')
    el.setAttribute('data-ncm-card-state', missing ? 'missing' : 'error')
    var note = h('span', 'ncm-pc__note')
    if (missing) {
      note.textContent = '网易云音乐上找不到这个内容'
    } else {
      note.textContent = e && e.status === 429 ? '请求太频繁，卡片暂时加载不出来' : '卡片暂时加载不出来'
      var retry = h('button', 'ncm-pc__retry', '重试')
      retry.type = 'button'
      retry.setAttribute('data-ncm-card-retry', '')
      note.appendChild(document.createTextNode(' '))
      note.appendChild(retry)
    }
    el.appendChild(note)
  }

  function retry(el) {
    var info = el.__ncmCard
    if (!info) return
    restoreFallback(el)
    el.classList.remove('is-error', 'is-missing')
    el.__ncmCard = null
    process(el)
  }

  function playLink(href, className, title) {
    var link = h('a', className)
    link.href = href
    link.setAttribute('aria-label', (sitePath(href) ? '在音乐页播放 ' : '在网易云音乐打开 ') + title)
    if (!sitePath(href)) {
      link.target = '_blank'
      link.rel = 'noopener noreferrer'
    }
    return link
  }

  function availabilityBadge(availability) {
    if (availability === 'trial') return h('span', 'ncm-pc__badge is-trial is-predict', '可能仅试听')
    if (availability === 'unavailable') return h('span', 'ncm-pc__badge is-failed is-predict', '可能不可播')
    return null
  }

  document.addEventListener('click', function (e) {
    var button = e.target && e.target.closest?.('[data-ncm-card-retry]')
    var card = button && button.closest('.ncm-card[data-ncm-card]')
    if (card) retry(card)
  })

  function scan() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-ncm-card]'), process)
  }

  var pending = false
  function scheduleScan() {
    if (pending) return
    pending = true
    var run = function () {
      pending = false
      scan()
    }
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run)
    else setTimeout(run, 16)
  }

  function init() {
    scan()
    ;['swup:page:view', 'turbo:load', 'pjax:complete'].forEach(function (name) {
      document.addEventListener(name, scheduleScan)
    })
    if (typeof MutationObserver === 'function' && document.body) {
      new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
          var r = records[i]
          if (!r.addedNodes.length) continue
          var t = r.target
          if (t && t.closest && t.closest('.ncm-card')) continue
          scheduleScan()
          return
        }
      }).observe(document.body, { childList: true, subtree: true })
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init)
  else init()
})()
