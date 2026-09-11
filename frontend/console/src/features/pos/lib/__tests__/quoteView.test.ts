import { describe, expect, it } from 'vitest'
import { visibleQuote } from '../quoteView'

const breakdown = { subtotalMinor: 45_000, grandTotalMinor: 45_000 }

describe('visibleQuote', () => {
  it('shows the quote while the cart has lines', () => {
    expect(visibleQuote(3, breakdown)).toBe(breakdown)
  })

  it('shows nothing while the quote is still absent', () => {
    expect(visibleQuote(3, undefined)).toBeNull()
  })

  it('shows NOTHING for an empty cart, even when a previous quote is still around', () => {
    // The bug: ring an item, remove it — the last non-empty quote lingered as placeholder data
    // and the deck kept the old total under an empty list.
    expect(visibleQuote(0, breakdown)).toBeNull()
  })
})
