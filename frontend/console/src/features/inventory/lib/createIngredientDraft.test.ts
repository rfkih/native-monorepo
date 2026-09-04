import { describe, expect, it } from 'vitest'
import {
  findExistingIngredientByName,
  parseNewIngredientDraft,
  type NewIngredientDraft,
} from './createIngredientDraft'

function draft(overrides: Partial<NewIngredientDraft> = {}): NewIngredientDraft {
  return { name: 'Tepung terigu', unitChoice: 'kg', ...overrides }
}

describe('parseNewIngredientDraft', () => {
  it('parses a display-unit choice (kg) into the stored base unit + label', () => {
    expect(parseNewIngredientDraft('outlet-1', draft())).toEqual({
      businessId: 'outlet-1',
      name: 'Tepung terigu',
      unit: 'g',
      displayUnit: 'kg',
    })
  })

  it('parses a base-unit choice (pcs) with no display label', () => {
    expect(parseNewIngredientDraft('outlet-1', draft({ unitChoice: 'pcs' }))).toEqual({
      businessId: 'outlet-1',
      name: 'Tepung terigu',
      unit: 'pcs',
      displayUnit: null,
    })
  })

  it('trims the name', () => {
    expect(parseNewIngredientDraft('outlet-1', draft({ name: '  Gula pasir  ' }))?.name).toBe(
      'Gula pasir',
    )
  })

  it('rejects a missing outlet', () => {
    expect(parseNewIngredientDraft('', draft())).toBeNull()
  })

  it('rejects a blank name', () => {
    expect(parseNewIngredientDraft('outlet-1', draft({ name: '   ' }))).toBeNull()
  })
})

describe('findExistingIngredientByName', () => {
  const ingredients = [{ name: 'Tepung Terigu' }, { name: 'Gula Pasir' }]

  it('matches case-insensitively and trims whitespace', () => {
    expect(findExistingIngredientByName('tepung terigu', ingredients)).toBe(ingredients[0])
    expect(findExistingIngredientByName('  GULA PASIR  ', ingredients)).toBe(ingredients[1])
  })

  it('is null for no match', () => {
    expect(findExistingIngredientByName('Minyak goreng', ingredients)).toBeNull()
  })

  it('is null for a blank input', () => {
    expect(findExistingIngredientByName('', ingredients)).toBeNull()
    expect(findExistingIngredientByName('   ', ingredients)).toBeNull()
  })
})
