import { describe, expect, it } from 'vitest'
import { parseInventoryLine, type InventoryLineDraft } from './ingredientLink'

const IDR = 'IDR'
const USD = 'USD'

const kg = { unit: 'g', displayUnit: 'kg' }
const pcs = { unit: 'pcs', displayUnit: null }

function draft(overrides: Partial<InventoryLineDraft> = {}): InventoryLineDraft {
  return {
    ingredientId: 'ing-1',
    ingredientName: 'Tepung terigu',
    qtyInput: '1.5',
    totalInput: '20000',
    ...overrides,
  }
}

describe('parseInventoryLine', () => {
  it('maps a complete draft to the wire shape: quantity 1, total as unitPriceMinor', () => {
    expect(parseInventoryLine(draft(), kg, IDR)).toEqual({
      description: 'Tepung terigu',
      quantity: 1,
      unitPriceMinor: 20_000,
      ingredientId: 'ing-1',
      ingredientName: 'Tepung terigu',
      ingredientQtyBase: 1500,
    })
  })

  it('converts the display-unit qty to the base integer (kg -> g)', () => {
    expect(parseInventoryLine(draft({ qtyInput: '0.25' }), kg, IDR)?.ingredientQtyBase).toBe(250)
  })

  it('accepts a whole quantity for a base-unit ingredient (pcs)', () => {
    expect(parseInventoryLine(draft({ qtyInput: '12' }), pcs, IDR)?.ingredientQtyBase).toBe(12)
  })

  it('rejects a fractional quantity for a base-unit ingredient', () => {
    expect(parseInventoryLine(draft({ qtyInput: '1.5' }), pcs, IDR)).toBeNull()
  })

  it('scales the total by the currency exponent (USD cents)', () => {
    expect(parseInventoryLine(draft({ totalInput: '12.50' }), kg, USD)?.unitPriceMinor).toBe(1250)
  })

  it('rejects no ingredient chosen (id blank)', () => {
    expect(parseInventoryLine(draft({ ingredientId: '' }), kg, IDR)).toBeNull()
  })

  it('rejects an unresolved ingredient (stale/unknown id)', () => {
    expect(parseInventoryLine(draft(), null, IDR)).toBeNull()
  })

  it('rejects a zero, blank, or negative quantity', () => {
    expect(parseInventoryLine(draft({ qtyInput: '' }), kg, IDR)).toBeNull()
    expect(parseInventoryLine(draft({ qtyInput: '0' }), kg, IDR)).toBeNull()
    expect(parseInventoryLine(draft({ qtyInput: '-1' }), kg, IDR)).toBeNull()
  })

  it('rejects a zero, blank, or negative total', () => {
    expect(parseInventoryLine(draft({ totalInput: '' }), kg, IDR)).toBeNull()
    expect(parseInventoryLine(draft({ totalInput: '0' }), kg, IDR)).toBeNull()
    expect(parseInventoryLine(draft({ totalInput: '-1' }), kg, IDR)).toBeNull()
  })
})
