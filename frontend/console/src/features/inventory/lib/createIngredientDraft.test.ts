import { describe, expect, it } from 'vitest'
import {
  findExistingIngredientByName,
  parseNewIngredientDraft,
  type NewIngredientDraft,
} from './createIngredientDraft'

function draft(overrides: Partial<NewIngredientDraft> = {}): NewIngredientDraft {
  return { name: 'Tepung terigu', unitChoice: 'kg', packSizeInput: '', ...overrides }
}

describe('parseNewIngredientDraft', () => {
  it('parses a display-unit choice (kg) into the stored base unit + label', () => {
    expect(parseNewIngredientDraft('outlet-1', draft())).toEqual({
      businessId: 'outlet-1',
      name: 'Tepung terigu',
      unit: 'g',
      displayUnit: 'kg',
      packSize: null,
    })
  })

  it('parses a base-unit choice (pcs) with no display label', () => {
    expect(parseNewIngredientDraft('outlet-1', draft({ unitChoice: 'pcs' }))).toEqual({
      businessId: 'outlet-1',
      name: 'Tepung terigu',
      unit: 'pcs',
      displayUnit: null,
      packSize: null,
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

  describe('pack size ("Isi per kemasan")', () => {
    it('is null (no default) when blank', () => {
      expect(parseNewIngredientDraft('outlet-1', draft({ packSizeInput: '' }))?.packSize).toBeNull()
    })

    it('converts a SHOWN-unit whole number to BASE for a kg-display choice (25 kg -> 25000 g)', () => {
      expect(
        parseNewIngredientDraft('outlet-1', draft({ unitChoice: 'kg', packSizeInput: '25' }))
          ?.packSize,
      ).toBe(25_000)
    })

    it('accepts a decimal pack size for a kg-display choice (2.5 kg -> 2500 g)', () => {
      expect(
        parseNewIngredientDraft('outlet-1', draft({ unitChoice: 'kg', packSizeInput: '2.5' }))
          ?.packSize,
      ).toBe(2500)
    })

    it('accepts a whole pack size for a base-unit choice (pcs, e.g. tortilla pack of 20)', () => {
      expect(
        parseNewIngredientDraft('outlet-1', draft({ unitChoice: 'pcs', packSizeInput: '20' }))
          ?.packSize,
      ).toBe(20)
    })

    it('rejects a fractional pack size for a whole-count choice (pcs)', () => {
      expect(
        parseNewIngredientDraft('outlet-1', draft({ unitChoice: 'pcs', packSizeInput: '2.5' })),
      ).toBeNull()
    })

    it('rejects a zero or negative pack size', () => {
      expect(parseNewIngredientDraft('outlet-1', draft({ packSizeInput: '0' }))).toBeNull()
      expect(parseNewIngredientDraft('outlet-1', draft({ packSizeInput: '-5' }))).toBeNull()
    })
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
