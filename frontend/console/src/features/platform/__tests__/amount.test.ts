import { describe, expect, it } from 'vitest'
import { deriveFeeMinor, parsePlatformAmountInput } from '../amount'

describe('parsePlatformAmountInput', () => {
  it('converts a whole-rupiah amount to minor units (IDR has zero minor-unit digits)', () => {
    expect(parsePlatformAmountInput('45000', 'IDR')).toBe(45_000)
  })

  it('rejects a grouping-separator on a zero-exponent currency instead of mis-scaling', () => {
    expect(parsePlatformAmountInput('45.000', 'IDR')).toBeNull()
    expect(parsePlatformAmountInput('45,000', 'IDR')).toBeNull()
  })

  it('converts a decimal USD amount to cents', () => {
    expect(parsePlatformAmountInput('12.5', 'USD')).toBe(1_250)
  })

  it('rounds a fractional-cent USD amount to the nearest cent', () => {
    expect(parsePlatformAmountInput('12.505', 'USD')).toBe(1_251)
  })

  it('tolerates surrounding whitespace', () => {
    expect(parsePlatformAmountInput('  15000  ', 'IDR')).toBe(15_000)
  })

  it('returns null for blank input', () => {
    expect(parsePlatformAmountInput('', 'IDR')).toBeNull()
    expect(parsePlatformAmountInput('   ', 'IDR')).toBeNull()
  })

  it('returns null for non-numeric input', () => {
    expect(parsePlatformAmountInput('abc', 'IDR')).toBeNull()
  })

  it('returns null for a negative amount regardless of allowZero', () => {
    expect(parsePlatformAmountInput('-5000', 'IDR')).toBeNull()
    expect(parsePlatformAmountInput('-5000', 'IDR', { allowZero: true })).toBeNull()
  })

  it('rejects zero by default (the gross-settled field, backend @Positive)', () => {
    expect(parsePlatformAmountInput('0', 'IDR')).toBeNull()
  })

  it('accepts zero when allowZero is set (the net-received field, backend @PositiveOrZero — a payout fully eaten by fees)', () => {
    expect(parsePlatformAmountInput('0', 'IDR', { allowZero: true })).toBe(0)
  })

  it('returns null for non-finite input', () => {
    expect(parsePlatformAmountInput('Infinity', 'IDR')).toBeNull()
    expect(parsePlatformAmountInput('NaN', 'IDR')).toBeNull()
  })
})

describe('deriveFeeMinor', () => {
  it('derives gross minus net', () => {
    expect(deriveFeeMinor(100_000, 88_000)).toBe(12_000)
  })

  it('is zero for a fee-free payout (net === gross)', () => {
    expect(deriveFeeMinor(50_000, 50_000)).toBe(0)
  })

  it('is null until both amounts are ready', () => {
    expect(deriveFeeMinor(null, 50_000)).toBeNull()
    expect(deriveFeeMinor(50_000, null)).toBeNull()
    expect(deriveFeeMinor(null, null)).toBeNull()
  })
})
