/**
 * qty.test.ts — the counted-quantity parser is deliberately fail-closed (rule 9's "never silently
 * wrong money" spirit extended to stock counts): invalid input returns null so the caller blocks
 * submission, unlike the money parsers which coerce to 0.
 */
import { describe, expect, it } from 'vitest'
import { formatQty, formatSignedQty, parseQtyInput } from '../qty'

describe('parseQtyInput', () => {
  it('parses a non-negative integer', () => {
    expect(parseQtyInput('12')).toBe(12)
    expect(parseQtyInput('0')).toBe(0)
  })

  it('blank input is null (not yet counted), never coerced to 0', () => {
    expect(parseQtyInput('')).toBeNull()
    expect(parseQtyInput('   ')).toBeNull()
  })

  it('negative, fractional, and non-numeric input are null (fail-closed)', () => {
    expect(parseQtyInput('-1')).toBeNull()
    expect(parseQtyInput('1.5')).toBeNull()
    expect(parseQtyInput('abc')).toBeNull()
  })
})

describe('formatQty / formatSignedQty', () => {
  it('formats a plain count via Intl grouping', () => {
    expect(formatQty(1234, 'en-US')).toBe('1,234')
  })

  it('signs a non-zero variance and omits the sign at zero', () => {
    expect(formatSignedQty(3, 'en-US')).toBe('+3')
    expect(formatSignedQty(-2, 'en-US')).toBe('-2')
    expect(formatSignedQty(0, 'en-US')).toBe('0')
  })
})
