import { describe, expect, it } from 'vitest'
import { availableSuggestions, STANDARD_DELIVERY_PLATFORMS } from '../suggestedChannels'

describe('availableSuggestions', () => {
  it('returns all standard platforms when the roster is empty', () => {
    expect(availableSuggestions([])).toEqual(STANDARD_DELIVERY_PLATFORMS)
  })

  it('excludes a suggestion whose code already exists', () => {
    expect(availableSuggestions(['GOFOOD'])).toEqual([
      { code: 'GRABFOOD', name: 'GrabFood' },
      { code: 'SHOPEEFOOD', name: 'ShopeeFood' },
    ])
  })

  it('compares codes case-insensitively', () => {
    expect(availableSuggestions(['gofood', 'GrabFood'])).toEqual([
      { code: 'SHOPEEFOOD', name: 'ShopeeFood' },
    ])
  })

  it('returns an empty list once every standard platform already exists', () => {
    expect(availableSuggestions(['GOFOOD', 'GRABFOOD', 'SHOPEEFOOD'])).toEqual([])
  })

  it('ignores existing codes that are not standard platforms', () => {
    expect(availableSuggestions(['CASH', 'QRIS'])).toEqual(STANDARD_DELIVERY_PLATFORMS)
  })
})
