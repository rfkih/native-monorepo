import { describe, expect, it } from 'vitest'
import { parseInventoryLine, parsePackedQtyBase, type InventoryLineDraft } from './ingredientLink'

const IDR = 'IDR'
const USD = 'USD'

const kg = { unit: 'g', displayUnit: 'kg' }
const pcs = { unit: 'pcs', displayUnit: null }

function draft(overrides: Partial<InventoryLineDraft> = {}): InventoryLineDraft {
  return {
    description: 'Tepung terigu',
    ingredientId: 'ing-1',
    ingredientName: 'Tepung terigu',
    qtyInput: '1.5',
    totalInput: '20000',
    packSizeInput: '',
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

  it('sends the description INDEPENDENTLY of the ingredient name ("Nama di nota berbeda")', () => {
    expect(
      parseInventoryLine(draft({ description: 'AYAM BROILER FROZEN 1KG' }), kg, IDR),
    ).toMatchObject({
      description: 'AYAM BROILER FROZEN 1KG',
      ingredientId: 'ing-1',
      ingredientName: 'Tepung terigu',
    })
  })

  it('trims the description', () => {
    expect(parseInventoryLine(draft({ description: '  Ayam fillet  ' }), kg, IDR)?.description).toBe(
      'Ayam fillet',
    )
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

  it('rejects a blank description even when an ingredient IS linked', () => {
    expect(parseInventoryLine(draft({ description: '   ' }), kg, IDR)).toBeNull()
  })

  it('rejects no ingredient chosen (id blank), even with a description', () => {
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

  describe('pack size ("Isi per kemasan") — receipt says "1 pcs" for a multi-unit pack', () => {
    it('is unchanged when no pack size is entered', () => {
      expect(parseInventoryLine(draft({ packSizeInput: '' }), kg, IDR)?.ingredientQtyBase).toBe(1500)
    })

    it('multiplies packs × per-pack qty for a base-unit ingredient (the tortilla case)', () => {
      // Receipt: "TORTILLA 1 PCS" — one pack, 20 tortillas inside; the bill still shows quantity 1.
      const parsed = parseInventoryLine(draft({ qtyInput: '1', packSizeInput: '20' }), pcs, IDR)
      expect(parsed).toMatchObject({ quantity: 1, ingredientQtyBase: 20 })
    })

    it('keeps quantity 1 and unitPriceMinor as the line TOTAL regardless of pack maths', () => {
      const parsed = parseInventoryLine(
        draft({ qtyInput: '1', totalInput: '50000', packSizeInput: '20' }),
        pcs,
        IDR,
      )
      expect(parsed).toMatchObject({ quantity: 1, unitPriceMinor: 50_000, ingredientQtyBase: 20 })
    })

    it('multiplies packs × per-pack qty for a kg-display ingredient (packs × N × 1000)', () => {
      const parsed = parseInventoryLine(draft({ qtyInput: '2', packSizeInput: '5' }), kg, IDR)
      expect(parsed?.ingredientQtyBase).toBe(2 * 5 * 1000)
    })

    it('computes exactly what a typo would produce (the scale-error safety net)', () => {
      const parsed = parseInventoryLine(draft({ qtyInput: '1', packSizeInput: '200' }), pcs, IDR)
      expect(parsed?.ingredientQtyBase).toBe(200)
    })

    it('rejects a fractional, zero, or negative pack size', () => {
      expect(parseInventoryLine(draft({ qtyInput: '1', packSizeInput: '2.5' }), pcs, IDR)).toBeNull()
      expect(parseInventoryLine(draft({ qtyInput: '1', packSizeInput: '0' }), pcs, IDR)).toBeNull()
      expect(parseInventoryLine(draft({ qtyInput: '1', packSizeInput: '-5' }), pcs, IDR)).toBeNull()
    })

    it('rejects a fractional, zero, or negative pack COUNT once pack size is set', () => {
      expect(parseInventoryLine(draft({ qtyInput: '1.5', packSizeInput: '20' }), pcs, IDR)).toBeNull()
      expect(parseInventoryLine(draft({ qtyInput: '0', packSizeInput: '20' }), pcs, IDR)).toBeNull()
      expect(parseInventoryLine(draft({ qtyInput: '-1', packSizeInput: '20' }), pcs, IDR)).toBeNull()
    })
  })
})

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

  it('rejects a non-integer, zero, or negative pack size', () => {
    expect(parsePackedQtyBase('1', '1.5', kg)).toBeNull()
    expect(parsePackedQtyBase('1', '0', kg)).toBeNull()
    expect(parsePackedQtyBase('1', '-2', kg)).toBeNull()
  })

  it('rejects a non-integer, zero, or negative pack count', () => {
    expect(parsePackedQtyBase('1.5', '20', pcs)).toBeNull()
    expect(parsePackedQtyBase('0', '20', pcs)).toBeNull()
    expect(parsePackedQtyBase('-1', '20', pcs)).toBeNull()
  })
})
