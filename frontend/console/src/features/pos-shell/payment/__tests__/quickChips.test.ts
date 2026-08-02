import { describe, expect, it } from 'vitest'
import { quickChips } from '../quickChips'

describe('quickChips', () => {
  it('IDR: exact + the preset chips that exceed the total', () => {
    expect(quickChips(35_000, 'IDR')).toEqual([35_000, 50_000, 100_000])
    expect(quickChips(60_000, 'IDR')).toEqual([60_000, 100_000])
  })

  it('IDR: only the exact chip once the total tops every preset', () => {
    expect(quickChips(150_000, 'IDR')).toEqual([150_000])
  })

  it('IDR: a preset equal to the total is NOT offered again (strictly greater)', () => {
    expect(quickChips(50_000, 'IDR')).toEqual([50_000, 100_000])
  })

  it('non-IDR: exact + round-up multiples of 500 and 1000 minor units', () => {
    expect(quickChips(1_234, 'USD')).toEqual([1_234, 1_500, 2_000])
  })

  it('non-IDR: skips a round-up that equals the total or duplicates the previous chip', () => {
    expect(quickChips(1_500, 'USD')).toEqual([1_500, 2_000])
    expect(quickChips(2_000, 'USD')).toEqual([2_000])
  })
})
