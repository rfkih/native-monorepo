import { describe, expect, it } from 'vitest'
import { needsUnitConversion, previewConversion } from '../lib/unitConversion'

describe('needsUnitConversion', () => {
  it('flags a base unit nothing can be cooked from', () => {
    // A pack has nothing beneath it: the smallest expressible use is one whole pack, and nobody
    // puts a kilogram of sauce in one kebab. This is the ingredient that ends up in no recipe.
    expect(needsUnitConversion({ unit: 'pack' })).toBe(true)
    expect(needsUnitConversion({ unit: 'kg' })).toBe(true) // legacy: kg stored as the BASE
    expect(needsUnitConversion({ unit: 'liter' })).toBe(true)
  })

  it('leaves the units a recipe can actually be written in alone', () => {
    // pcs is fine — half a bread roll is not a thing, and 1 is a legitimate portion.
    expect(needsUnitConversion({ unit: 'g' })).toBe(false)
    expect(needsUnitConversion({ unit: 'ml' })).toBe(false)
    expect(needsUnitConversion({ unit: 'pcs' })).toBe(false)
  })
})

describe('previewConversion', () => {
  it('previews the new stock so the owner sees the result before committing', () => {
    // "1 pack = 1000 g", 2 packs on hand -> 2000 g. Seeing 2000 is what makes the factor checkable.
    const preview = previewConversion('kg', '1000', 2)
    expect(preview).toEqual({
      ok: true,
      toUnit: 'g',
      toDisplayUnit: 'kg',
      factor: 1000,
      newStockQty: 2000,
    })
  })

  it('maps the picker choice through to base + display, not to the label typed', () => {
    // Choosing "kg" must store grams with a kg label — the same rule the create form follows, so a
    // converted ingredient is indistinguishable from a correctly-created one.
    const preview = previewConversion('pcs', '12', 3)
    expect(preview).toEqual({
      ok: true,
      toUnit: 'pcs',
      toDisplayUnit: null,
      factor: 12,
      newStockQty: 36,
    })
  })

  it('rejects a factor that is not a positive whole number', () => {
    // A fractional factor means converting toward a COARSER unit, which loses the precision this
    // operation exists to gain.
    for (const bad of ['', '  ', '0', '-5', '1.5', 'abc']) {
      expect(previewConversion('kg', bad, 2), bad).toEqual({ ok: false, reason: 'factor' })
    }
  })

  it('refuses a result the server column cannot hold, before the request is sent', () => {
    // 1.7 tonnes of meat in grams converted to milligrams needs 1.7 billion. Catching it here means
    // a field error rather than a 422 after the owner has committed to the dialog.
    expect(previewConversion('g', '10000', 1_700_000)).toEqual({ ok: false, reason: 'overflow' })
  })
})
