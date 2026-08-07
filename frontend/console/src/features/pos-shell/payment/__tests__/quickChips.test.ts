import { describe, expect, it } from 'vitest'
import { quickChips } from '../quickChips'

describe('quickChips', () => {
  it('IDR: exact + banknote round-ups of the total (small bill)', () => {
    // 35k → hand over 40k (two 20s), a 50, or a 100.
    expect(quickChips(35_000, 'IDR')).toEqual([35_000, 40_000, 50_000, 100_000])
    // 60k divides every small note exactly — only the 100k note remains a real over-tender.
    expect(quickChips(60_000, 'IDR')).toEqual([60_000, 100_000])
  })

  it('IDR: bills above Rp100k still get natural round-ups (the old presets went silent here)', () => {
    expect(quickChips(150_000, 'IDR')).toEqual([150_000, 160_000, 200_000])
    // 320k → 350k, 400k — what a wallet actually produces (20k divides 320k exactly).
    expect(quickChips(320_000, 'IDR')).toEqual([320_000, 350_000, 400_000])
  })

  it('IDR: a very large bill keeps the stack-of-hundreds chip (100k is the largest banknote)', () => {
    expect(quickChips(3_350_000, 'IDR')).toEqual([3_350_000, 3_400_000])
  })

  it('IDR: a round-up equal to the total is NOT offered again (strictly greater)', () => {
    expect(quickChips(50_000, 'IDR')).toEqual([50_000, 60_000, 100_000])
    // An exactly-note-divisible total needs no over-tender chip at all.
    expect(quickChips(100_000, 'IDR')).toEqual([100_000])
  })

  it('non-IDR: exact + round-up multiples of 500 and 1000 minor units', () => {
    expect(quickChips(1_234, 'USD')).toEqual([1_234, 1_500, 2_000])
  })

  it('non-IDR: skips a round-up that equals the total or duplicates the previous chip', () => {
    expect(quickChips(1_500, 'USD')).toEqual([1_500, 2_000])
    expect(quickChips(2_000, 'USD')).toEqual([2_000])
  })
})
