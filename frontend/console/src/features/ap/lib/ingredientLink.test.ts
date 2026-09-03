import { describe, expect, it } from 'vitest'
import { parseIngredientLink, type IngredientLinkDraft } from './ingredientLink'

const kg = { unit: 'g', displayUnit: 'kg' }
const pcs = { unit: 'pcs', displayUnit: null }

function draft(overrides: Partial<IngredientLinkDraft> = {}): IngredientLinkDraft {
  return {
    ingredientId: 'ing-1',
    ingredientName: 'Tepung terigu',
    qtyInput: '1.5',
    ...overrides,
  }
}

describe('parseIngredientLink', () => {
  it('is empty-but-valid when nothing was entered (a plain or unlinked inventory line)', () => {
    expect(parseIngredientLink(draft({ ingredientId: '', ingredientName: '', qtyInput: '' }), null)).toEqual({
      link: null,
      valid: true,
    })
  })

  it('parses a complete trio, converting the display-unit qty to the base integer (kg -> g)', () => {
    expect(parseIngredientLink(draft(), kg)).toEqual({
      link: { ingredientId: 'ing-1', ingredientName: 'Tepung terigu', ingredientQtyBase: 1500 },
      valid: true,
    })
  })

  it('accepts a whole quantity for a base-unit ingredient (pcs)', () => {
    expect(parseIngredientLink(draft({ qtyInput: '12' }), pcs)).toEqual({
      link: { ingredientId: 'ing-1', ingredientName: 'Tepung terigu', ingredientQtyBase: 12 },
      valid: true,
    })
  })

  it('is invalid — an ingredient picked but no quantity entered (partial, blocks submit)', () => {
    expect(parseIngredientLink(draft({ qtyInput: '' }), kg)).toEqual({ link: null, valid: false })
  })

  it('is invalid — a quantity entered but no ingredient chosen', () => {
    expect(parseIngredientLink(draft({ ingredientId: '', ingredientName: '' }), null)).toEqual({
      link: null,
      valid: false,
    })
  })

  it('is invalid — the chosen ingredient id does not resolve (stale/unknown)', () => {
    expect(parseIngredientLink(draft(), null)).toEqual({ link: null, valid: false })
  })

  it('is invalid — a zero or negative quantity', () => {
    expect(parseIngredientLink(draft({ qtyInput: '0' }), kg)).toEqual({ link: null, valid: false })
    expect(parseIngredientLink(draft({ qtyInput: '-1' }), kg)).toEqual({ link: null, valid: false })
  })

  it('is invalid — a fractional quantity on a base-unit (whole-count) ingredient', () => {
    expect(parseIngredientLink(draft({ qtyInput: '1.5' }), pcs)).toEqual({ link: null, valid: false })
  })
})
