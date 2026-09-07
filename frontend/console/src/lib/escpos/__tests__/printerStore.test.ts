import { describe, expect, it } from 'vitest'
import { autoPrintEnabled, shouldKickDrawer, type PrinterConfig } from '../printerStore'

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

describe('autoPrintEnabled', () => {
  const cfg = (autoPrint?: boolean): PrinterConfig => ({
    transport: 'usb',
    paper: 58,
    drawerKick: false,
    ...(autoPrint === undefined ? {} : { autoPrint }),
  })

  it('is ON when the device never made a choice', () => {
    // Pairing a printer IS the opt-in: a till that set one up wants its receipts on paper, and
    // this used to read as `?? false`, so every device had to rediscover a buried toggle.
    expect(autoPrintEnabled(cfg(undefined))).toBe(true)
  })

  it('respects an explicit choice in both directions', () => {
    expect(autoPrintEnabled(cfg(true))).toBe(true)
    // An operator who prints on request only turned it off — flipping the DEFAULT must not
    // silently overrule a stored `false`.
    expect(autoPrintEnabled(cfg(false))).toBe(false)
  })

  it('is OFF with no printer configured at all', () => {
    // The guard that makes flipping the default safe: a till with no printer is untouched.
    expect(autoPrintEnabled(null)).toBe(false)
  })
})
