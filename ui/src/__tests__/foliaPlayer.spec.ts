import { afterEach, describe, expect, it, vi } from 'vitest'
import { FoliaPlayer } from '../../../frontstage/mini-player/foliaPlayer'
import type { FoliaFrame, PlaybackSession } from '../../../frontstage/mini-player/playbackSession'

const track = { id: '123', title: 'Test track', artist: 'Test artist' }
const session: PlaybackSession = {
  queue: [track],
  index: 0, time: 12, duration: 90, playing: true, at: 1, tab: 'test',
}

function connectEngine() {
  const frame = document.querySelector<FoliaFrame>('iframe[data-ncm-folia-frame]')!
  const engine = {
    view: () => ({
      track, size: 1, index: 0, position: 12, duration: 90,
      phase: 'playing' as const, trial: false, note: '',
    }),
    play: vi.fn(),
    playPlaylist: vi.fn(),
    toggle: vi.fn(),
    step: vi.fn(),
    seek: vi.fn(),
    openLyrics: vi.fn(),
    subscribe: vi.fn(() => () => {}),
  }
  frame.ncmPlayback = engine
  frame.dispatchEvent(new Event('ncm:playback-ready'))
  return { frame, engine }
}

afterEach(() => {
  document.body.replaceChildren()
  vi.unstubAllGlobals()
})

describe('Folia mini player', () => {
  it('starts the configured playlist through Folia without Swup', async () => {
    const player = new FoliaPlayer('42', {
      readPlaybackSession: () => null,
      readResumablePlaybackSession: () => null,
    }, vi.fn())
    expect(document.querySelector('iframe')).toBeNull()
    player.toggle()
    const { engine } = connectEngine()
    await vi.waitFor(() => expect(engine.playPlaylist).toHaveBeenCalledExactlyOnceWith('42', expect.any(AbortSignal)))
    expect(player.view().track.id).toBe('123')
    expect(document.querySelectorAll('iframe')).toHaveLength(1)
  })

  it('attaches late Swup navigation to the Folia frame that already restored playback', async () => {
    const player = new FoliaPlayer('42', {
      readPlaybackSession: () => session,
      readResumablePlaybackSession: () => session,
    }, vi.fn())
    player.resume()
    const { frame, engine } = connectEngine()
    await vi.waitFor(() => expect(engine.play).toHaveBeenCalledExactlyOnceWith(session))

    vi.stubGlobal('HTMLDialogElement', class { showModal() {} })
    const swup = { navigate: vi.fn(), hooks: { on: vi.fn(), before: vi.fn(), once: vi.fn() } }
    player.attachSwup(swup)
    const click = new MouseEvent('click', { cancelable: true })
    player.openLyrics(click, '/music?view=lyrics')

    expect(click.defaultPrevented).toBe(true)
    expect(swup.navigate).toHaveBeenCalledExactlyOnceWith('/music?view=lyrics')
    expect(document.querySelector('iframe')).toBe(frame)
    expect(frame.ncmPlayback).toBe(engine)
    expect(engine.play).toHaveBeenCalledTimes(1)
  })
})
