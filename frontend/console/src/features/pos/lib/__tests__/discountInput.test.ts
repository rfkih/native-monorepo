/**
 * discountInput.test.ts — the manual-discount parser is exponent-aware (rule 8): IDR has ZERO
 * minor digits (whole rupiah, matching backend libs/money), USD has two.
 */
import { describe, expect, it } from 'vitest'
import { parseDiscountInput } from '../discountInput'

describe('parseDiscountInput', () => {
  it('IDR (exponent 0): whole-rupiah input passes through unscaled', () => {
    expect(parseDiscountInput('15000', 'IDR')).toBe(15000)
  })

  it('IDR: fractional input rounds to whole rupiah', () => {
    expect(parseDiscountInput('100.4', 'IDR')).toBe(100)
    expect(parseDiscountInput('100.5', 'IDR')).toBe(101)
  })

  it('USD (exponent 2): major units scale to cents', () => {
    expect(parseDiscountInput('12.50', 'USD')).toBe(1250)
    expect(parseDiscountInput('12', 'USD')).toBe(1200)
  })

  it('empty and whitespace-only input parse to 0', () => {
    expect(parseDiscountInput('', 'IDR')).toBe(0)
    expect(parseDiscountInput('   ', 'IDR')).toBe(0)
  })

  it('invalid and negative input parse to 0 (never an error)', () => {
    expect(parseDiscountInput('abc', 'IDR')).toBe(0)
    expect(parseDiscountInput('-5', 'IDR')).toBe(0)
  })
})
