/**
 * displayPayload.test.ts — the BroadcastChannel drift tripwire (redesign P1). Every payload the
 * POS terminal builds for the customer display MUST satisfy displayChannel.ts's runtime
 * validator: a payload that fails isDisplayMessage is silently DROPPED by the receiver, which
 * freezes the customer-facing screen with no error anywhere. The protocol is ADR 0029 and does
 * not change in the redesign.
 */
import { describe, expect, it } from 'vitest'
import { billDisplayBreakdown, cartDisplayBreakdown, cartDisplayLines } from '../displayPayload'
import { isDisplayMessage } from '../../display/displayChannel'

describe('billDisplayBreakdown', () => {
  it('builds a valid CART_UPDATED payload from a bill running total (no lines by design)', () => {
    const breakdown = billDisplayBreakdown({ runningTotalMinor: 60_000, currency: 'IDR' })
    expect(isDisplayMessage({ type: 'CART_UPDATED', lines: [], breakdown })).toBe(true)
    expect(breakdown.grandTotalMinor).toBe(60_000)
    expect(breakdown.subtotalMinor).toBe(60_000)
  })
})

describe('cartDisplayLines', () => {
  const items = [{ id: 'm1', name: 'Nasi Goreng' }]

  it('resolves names from the menu and computes line totals', () => {
    const lines = cartDisplayLines(
      [{ menuItemId: 'm1', qty: 2, effectiveUnitPriceMinor: 45_000 }],
      items,
    )
    expect(lines).toEqual([
      { name: 'Nasi Goreng', qty: 2, unitPriceMinor: 45_000, lineTotalMinor: 90_000 },
    ])
    expect(isDisplayMessage({ type: 'CART_UPDATED', lines, breakdown: null })).toBe(true)
  })

  it('a cart line whose menu item vanished renders an empty name, never a crash', () => {
    const lines = cartDisplayLines(
      [{ menuItemId: 'gone', qty: 1, effectiveUnitPriceMinor: 1000 }],
      items,
    )
    expect(lines[0].name).toBe('')
    expect(isDisplayMessage({ type: 'CART_UPDATED', lines, breakdown: null })).toBe(true)
  })
})

describe('cartDisplayBreakdown', () => {
  it('passes the live quote through field-for-field', () => {
    const quote = {
      subtotalMinor: 71_000,
      discountMinor: 5_000,
      serviceChargeMinor: 0,
      taxMinor: 7_100,
      grandTotalMinor: 73_100,
      currency: 'IDR',
    }
    const breakdown = cartDisplayBreakdown(quote, {
      clientSubtotalMinor: 0,
      grandTotalMinor: 0,
      currency: 'IDR',
    })
    expect(breakdown).toEqual(quote)
    expect(isDisplayMessage({ type: 'CART_UPDATED', lines: [], breakdown })).toBe(true)
  })

  it('falls back to the client subtotal when no quote has resolved (offline / debounce window)', () => {
    const breakdown = cartDisplayBreakdown(null, {
      clientSubtotalMinor: 53_000,
      grandTotalMinor: 53_000,
      currency: 'IDR',
    })
    expect(breakdown.subtotalMinor).toBe(53_000)
    expect(breakdown.discountMinor).toBe(0)
    expect(isDisplayMessage({ type: 'CART_UPDATED', lines: [], breakdown })).toBe(true)
  })
})
