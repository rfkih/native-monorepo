import { describe, expect, it } from 'vitest'
import { parsePackedQtyBase } from './packQty'

const kg = { unit: 'g', displayUnit: 'kg' }
const pcs = { unit: 'pcs', displayUnit: null }

describe('parsePackedQtyBase', () => {
  it('falls through to the plain display-unit conversion when packSizeInput is blank', () => {
    expect(parsePackedQtyBase('1.5', '', kg)).toEqual({ packs: null, qtyBase: 1500 })
  })

  it('returns null (not a fallback) for an invalid plain quantity with no pack size', () => {
    expect(parsePackedQtyBase('', '', kg)).toBeNull()
    expect(parsePackedQtyBase('0', '', kg)).toBeNull()
  })

  it('multiplies packs × per-pack base qty', () => {
    expect(parsePackedQtyBase('1', '20', pcs)).toEqual({ packs: 1, qtyBase: 20 })
    expect(parsePackedQtyBase('3', '20', pcs)).toEqual({ packs: 3, qtyBase: 60 })
  })

  it('applies shownFactor on top of the pack size for a display-unit ingredient', () => {
    expect(parsePackedQtyBase('2', '5', kg)).toEqual({ packs: 2, qtyBase: 10_000 })
  })

  it('E3 — accepts a DECIMAL pack size for a kg/liter-display ingredient, same as any qty input', () => {
    expect(parsePackedQtyBase('1', '2.5', kg)).toEqual({ packs: 1, qtyBase: 2500 })
    expect(parsePackedQtyBase('3', '0.25', kg)).toEqual({ packs: 3, qtyBase: 750 })
  })

  it('computes exactly what a typo would produce (the scale-error safety net)', () => {
    expect(parsePackedQtyBase('1', '200', pcs)).toEqual({ packs: 1, qtyBase: 200 })
  })

  it('rejects a fractional pack size for a whole-count ingredient', () => {
    expect(parsePackedQtyBase('1', '1.5', pcs)).toBeNull()
  })

  it('rejects a zero or negative pack size', () => {
    expect(parsePackedQtyBase('1', '0', kg)).toBeNull()
    expect(parsePackedQtyBase('1', '-2', kg)).toBeNull()
    expect(parsePackedQtyBase('1', '-2.5', kg)).toBeNull()
  })

  it('rejects a non-integer, zero, or negative pack count', () => {
    expect(parsePackedQtyBase('1.5', '20', pcs)).toBeNull()
    expect(parsePackedQtyBase('0', '20', pcs)).toBeNull()
    expect(parsePackedQtyBase('-1', '20', pcs)).toBeNull()
  })
})
