import { describe, expect, it } from 'vitest'
import { avoidFloatingControls } from '../../../frontstage/mini-player/placement'

describe('floating player placement', () => {
  const size = { width: 44, height: 44 }
  const contact = { left: 1214, top: 835, right: 1434, bottom: 880 }
  const desktop = { left: 12, top: 12, right: 1429, bottom: 892 }

  it('moves above a contact control occupying the saved bottom-right position', () => {
    const preferred = { x: 1385, y: 838 }
    expect(avoidFloatingControls(preferred, desktop, size, [contact])).toEqual({ x: 1385, y: 783 })
    expect(avoidFloatingControls(preferred, desktop, size, [])).toEqual({ x: 1385, y: 838 })
  })

  it('keeps a separate user position while the contact control is visible', () => {
    const preferred = { x: 30, y: 830 }
    expect(avoidFloatingControls(preferred, desktop, size, [contact])).toEqual(preferred)
  })

  it('moves sideways when the remaining height cannot fit the player', () => {
    const landscape = { left: 12, top: 12, right: 388, bottom: 99 }
    const contact = { left: 280, top: 45, right: 388, bottom: 99 }
    expect(avoidFloatingControls({ x: 344, y: 55 }, landscape, size, [contact])).toEqual({ x: 228, y: 55 })
  })
})
