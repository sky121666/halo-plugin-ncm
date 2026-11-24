import { describe, expect, it } from 'vitest'
import {
  applyEdits, buttonPixels, buttonPosition, placePanel, readPositions,
} from '../../../../frontstage/mini-player/placement'

const disc = { width: 44, height: 44 }
const panel = { width: 288, height: 140 }
const wide = { left: 12, right: 988, top: 12, bottom: 588 }
const box = (x: number, y: number) => ({ left: x, top: y, right: x + 44, bottom: y + 44 })

describe('mini player positioning', () => {
  it('expands inward at the left and right corners and stays above the safe inset', () => {
    const left = placePanel(box(12, 544), panel, wide)
    const right = placePanel(box(944, 544), panel, wide)
    expect(left).toMatchObject({ side: 'right', x: 66, y: 448 })
    expect(right).toMatchObject({ side: 'left', x: 646, y: 448 })
  })

  it('falls back vertically when the card cannot fit beside the record', () => {
    const compact = { left: 12, right: 308, top: 12, bottom: 620 }
    expect(placePanel(box(12, 576), panel, compact)).toMatchObject({ side: 'top', x: 12, y: 426 })
    expect(placePanel(box(12, 12), panel, compact)).toMatchObject({ side: 'bottom', x: 12, y: 66 })
  })

  it('constrains the card in a short or occluded viewport', () => {
    const visible = { left: 12, right: 308, top: 160, bottom: 330 }
    expect(placePanel(box(130, 228), panel, visible)).toMatchObject({
      side: 'top', x: 12, y: 160, maximumHeight: 58,
    })
  })

  it('keeps a saved corner after resizing and clamps coordinates that leave the viewport', () => {
    const corner = buttonPosition({ x: -300, y: 950 }, wide, disc)
    expect(corner).toEqual({ x: 0, y: 1 })
    expect(buttonPixels(corner, { left: 18, right: 360, top: 36, bottom: 620 }, disc))
      .toEqual({ x: 18, y: 576 })
  })

  it('reads only valid coordinates and clears just the layout being reset', () => {
    const positions = readPositions({ wide: { x: 0.2, y: 0.5 }, compact: { x: -1, y: 0 }, unknown: {} })
    expect(positions).toEqual({ wide: { x: 0.2, y: 0.5 } })
    const merged = applyEdits(positions, { compact: { x: 0, y: 1 } })
    expect(applyEdits(merged, { wide: null })).toEqual({ compact: { x: 0, y: 1 } })
  })
})
