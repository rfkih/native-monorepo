import { describe, expect, it } from 'vitest'
import {
  minorToAmountInputValue,
  parseAmountInputToMinor,
  RECEIPT_MAX_BYTES,
  validateReceiptFile,
} from '../amount'

describe('parseAmountInputToMinor', () => {
  it('converts a whole-rupiah amount to minor units (IDR has zero minor-unit digits)', () => {
    expect(parseAmountInputToMinor('45000', 'IDR')).toBe(45_000)
  })

  it('converts a decimal USD amount to cents', () => {
    expect(parseAmountInputToMinor('12.5', 'USD')).toBe(1_250)
  })

  it('rounds a fractional-cent USD amount to the nearest cent', () => {
    expect(parseAmountInputToMinor('12.505', 'USD')).toBe(1_251) // 1250.5 -> rounds up
  })

  it('tolerates surrounding whitespace', () => {
    expect(parseAmountInputToMinor('  15000  ', 'IDR')).toBe(15_000)
  })

  it('returns null for blank input', () => {
    expect(parseAmountInputToMinor('', 'IDR')).toBeNull()
    expect(parseAmountInputToMinor('   ', 'IDR')).toBeNull()
  })

  it('returns null for non-numeric input', () => {
    expect(parseAmountInputToMinor('abc', 'IDR')).toBeNull()
  })

  it('returns null for zero or negative amounts', () => {
    expect(parseAmountInputToMinor('0', 'IDR')).toBeNull()
    expect(parseAmountInputToMinor('-5000', 'IDR')).toBeNull()
  })

  it('returns null for non-finite input', () => {
    expect(parseAmountInputToMinor('Infinity', 'IDR')).toBeNull()
    expect(parseAmountInputToMinor('NaN', 'IDR')).toBeNull()
  })
})

describe('minorToAmountInputValue', () => {
  it('round-trips a whole-rupiah amount back to an editable string', () => {
    expect(minorToAmountInputValue(45_000, 'IDR')).toBe('45000')
  })

  it('round-trips a USD amount with two decimal places', () => {
    expect(minorToAmountInputValue(1_250, 'USD')).toBe('12.50')
  })

  it('composes with parseAmountInputToMinor for a stable round trip', () => {
    const original = 987_650
    const asInput = minorToAmountInputValue(original, 'USD')
    expect(parseAmountInputToMinor(asInput, 'USD')).toBe(original)
  })
})

describe('validateReceiptFile', () => {
  it('accepts a jpeg within the size cap', () => {
    expect(validateReceiptFile({ size: 1024, type: 'image/jpeg' })).toEqual({ ok: true })
  })

  it('accepts a png/webp within the size cap', () => {
    expect(validateReceiptFile({ size: 1024, type: 'image/png' })).toEqual({ ok: true })
    expect(validateReceiptFile({ size: 1024, type: 'image/webp' })).toEqual({ ok: true })
  })

  it('rejects a file over the 5 MiB cap', () => {
    expect(validateReceiptFile({ size: RECEIPT_MAX_BYTES + 1, type: 'image/jpeg' })).toEqual({
      ok: false,
      reason: 'size',
    })
  })

  it('rejects an empty file', () => {
    expect(validateReceiptFile({ size: 0, type: 'image/jpeg' })).toEqual({
      ok: false,
      reason: 'size',
    })
  })

  it('rejects an unsupported MIME type', () => {
    expect(validateReceiptFile({ size: 1024, type: 'application/pdf' })).toEqual({
      ok: false,
      reason: 'type',
    })
  })

  it('accepts a file exactly at the size cap', () => {
    expect(validateReceiptFile({ size: RECEIPT_MAX_BYTES, type: 'image/png' })).toEqual({
      ok: true,
    })
  })
})
