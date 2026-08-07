import { describe, expect, it } from 'vitest'
import { shouldKickDrawer } from '../printerStore'

/**
 * The drawer-kick policy (P1 printing-flow hardening): the drawer must pop ONLY when the device
 * toggle is on AND the sale is a cash tender — a card/QRIS/other-tender receipt popping the drawer
 * is a loss-prevention gap, not a cosmetic one. Pure-function truth table, no DOM/context needed.
 */
describe('shouldKickDrawer', () => {
  it('kicks when the device toggle is on AND the sale is cash', () => {
    expect(shouldKickDrawer(true, true)).toBe(true)
  })

  it('never kicks for a non-cash tender, even with the device toggle on', () => {
    expect(shouldKickDrawer(true, false)).toBe(false)
  })

  it('never kicks when the device toggle is off, even for a cash tender', () => {
    expect(shouldKickDrawer(false, true)).toBe(false)
  })

  it('never kicks when both are off', () => {
    expect(shouldKickDrawer(false, false)).toBe(false)
  })
})
