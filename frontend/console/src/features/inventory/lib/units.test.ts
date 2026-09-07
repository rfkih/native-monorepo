import { describe, expect, it } from 'vitest'
import {
  allowsFraction,
  formatShownQty,
  formatSignedShownQty,
  parseShownQtyInput,
  sanitizeShownQtyInput,
  shownFactor,
  shownQtyInputValue,
  shownUnit,
  shownUnitCostMinor,
  storedToUnitSelection,
  toBaseQty,
  toDisplayQty,
  unitSelectionToStored,
} from './units'

const kg = { unit: 'g', displayUnit: 'kg' }
const liter = { unit: 'ml', displayUnit: 'liter' }
const pcs = { unit: 'pcs', displayUnit: null }

describe('unitSelectionToStored', () => {
  it('maps a display choice to base unit + label', () => {
    expect(unitSelectionToStored('kg')).toEqual({ unit: 'g', displayUnit: 'kg' })
    expect(unitSelectionToStored('liter')).toEqual({ unit: 'ml', displayUnit: 'liter' })
  })
  it('stores a base-unit choice as-is with no label', () => {
    expect(unitSelectionToStored('g')).toEqual({ unit: 'g', displayUnit: null })
    expect(unitSelectionToStored('pcs')).toEqual({ unit: 'pcs', displayUnit: null })
    expect(unitSelectionToStored('pack')).toEqual({ unit: 'pack', displayUnit: null })
  })
})

describe('storedToUnitSelection / shownUnit / shownFactor', () => {
  it('round-trips the picker choice', () => {
    expect(storedToUnitSelection(kg)).toBe('kg')
    expect(storedToUnitSelection(pcs)).toBe('pcs')
  })
  it('shows the display label when set, else the base unit', () => {
    expect(shownUnit(kg)).toBe('kg')
    expect(shownUnit(pcs)).toBe('pcs')
  })
  it('is factor 1000 for kg/liter, 1 for a base unit', () => {
    expect(shownFactor(kg)).toBe(1000)
    expect(shownFactor(liter)).toBe(1000)
    expect(shownFactor(pcs)).toBe(1)
    expect(allowsFraction(kg)).toBe(true)
    expect(allowsFraction(pcs)).toBe(false)
  })
})

describe('toDisplayQty / toBaseQty', () => {
  it('converts base grams to shown kg and back', () => {
    expect(toDisplayQty(1500, kg)).toBe(1.5)
    expect(toBaseQty(1.5, kg)).toBe(1500)
    expect(toBaseQty(0.75, kg)).toBe(750)
  })
  it('is identity for a base unit', () => {
    expect(toDisplayQty(12, pcs)).toBe(12)
    expect(toBaseQty(12, pcs)).toBe(12)
  })
  it('rounds a sub-gram display value to a whole base unit', () => {
    expect(toBaseQty(1.2345, kg)).toBe(1235) // 1234.5 → 1235
  })
})

describe('parseShownQtyInput', () => {
  it('accepts decimals for kg and converts to base grams', () => {
    expect(parseShownQtyInput('1.5', kg)).toBe(1500)
    expect(parseShownQtyInput('0.25', kg)).toBe(250)
  })
  it('rejects a fraction for a countable base unit', () => {
    expect(parseShownQtyInput('1.5', pcs)).toBeNull()
    expect(parseShownQtyInput('3', pcs)).toBe(3)
  })
  it('rejects empty, non-numeric, and negative input', () => {
    expect(parseShownQtyInput('', kg)).toBeNull()
    expect(parseShownQtyInput('abc', kg)).toBeNull()
    expect(parseShownQtyInput('-1', kg)).toBeNull()
  })
  it('accepts a COMMA decimal separator — the one an id-ID keypad offers', () => {
    expect(parseShownQtyInput('1,5', kg)).toBe(1500)
    expect(parseShownQtyInput('0,25', kg)).toBe(250)
    expect(parseShownQtyInput('1,5', pcs)).toBeNull() // still no half a pcs
  })
  it('refuses to guess at a grouped or exponential figure', () => {
    expect(parseShownQtyInput('1.234,5', kg)).toBeNull()
    expect(parseShownQtyInput('1,234.5', kg)).toBeNull()
    expect(parseShownQtyInput('1e3', kg)).toBeNull()
    expect(parseShownQtyInput('+2', kg)).toBeNull()
  })
})

describe('sanitizeShownQtyInput', () => {
  it('drops what the parse would reject and keeps the FIRST separator only', () => {
    expect(sanitizeShownQtyInput('1,5')).toBe('1,5')
    expect(sanitizeShownQtyInput('1.5')).toBe('1.5')
    expect(sanitizeShownQtyInput('1-2e3')).toBe('123')
    expect(sanitizeShownQtyInput('1,5,7')).toBe('1,57')
    expect(sanitizeShownQtyInput('1.234,5')).toBe('1.2345')
  })
  it('leaves a cleared field cleared', () => {
    expect(sanitizeShownQtyInput('')).toBe('')
    expect(sanitizeShownQtyInput('abc')).toBe('')
  })
})

describe('shownQtyInputValue — the seeded field value', () => {
  it('seeds in the operator locale, so the field matches the line above it', () => {
    expect(shownQtyInputValue(1500, kg, 'id-ID')).toBe('1,5')
    expect(shownQtyInputValue(1500, kg, 'en-US')).toBe('1.5')
  })
  it('never groups — a grouped seed would re-parse ambiguously', () => {
    expect(shownQtyInputValue(12_000, pcs, 'id-ID')).toBe('12000')
    expect(shownQtyInputValue(9_500_000, kg, 'id-ID')).toBe('9500')
  })
  it('round-trips through the parse', () => {
    for (const locale of ['id-ID', 'en-US']) {
      expect(parseShownQtyInput(shownQtyInputValue(1234, kg, locale), kg)).toBe(1234)
      expect(parseShownQtyInput(shownQtyInputValue(7, pcs, locale), pcs)).toBe(7)
    }
  })
})

describe('formatSignedShownQty', () => {
  it('signs the variance in the shown unit', () => {
    expect(formatSignedShownQty(-411, kg, 'en-US')).toBe('-0.411')
    expect(formatSignedShownQty(3, pcs, 'en-US')).toBe('+3')
    expect(formatSignedShownQty(0, pcs, 'en-US')).toBe('0')
  })
})

describe('shownUnitCostMinor — exact per-shown-unit cost from the total value', () => {
  it('derives kg cost from the total, NOT the per-gram cache ×1000', () => {
    // value 10000 over 1500 g: true per-kg = round(10000*1000/1500) = 6667.
    // The naive round(10000/1500)*1000 = 7*1000 = 7000 would be wrong — assert we avoid it.
    const ing = { stockQty: 1500, stockValueMinor: 10_000, unitCostMinor: 7, displayUnit: 'kg' }
    expect(shownUnitCostMinor(ing)).toBe(6667)
  })
  it('matches the server cache for a base unit (factor 1)', () => {
    const ing = { stockQty: 5, stockValueMinor: 10_000, unitCostMinor: 2000, displayUnit: null }
    expect(shownUnitCostMinor(ing)).toBe(2000)
  })
  it('is null when uncosted', () => {
    expect(
      shownUnitCostMinor({ stockQty: 1500, stockValueMinor: 0, unitCostMinor: null, displayUnit: 'kg' }),
    ).toBeNull()
  })
  it('falls back to the cache ×factor with no stock on hand', () => {
    const ing = { stockQty: 0, stockValueMinor: 0, unitCostMinor: 12, displayUnit: 'kg' }
    expect(shownUnitCostMinor(ing)).toBe(12_000)
  })
  it('does not return NaN when the server omits stockValueMinor (pre-ADR-0056 backend)', () => {
    const ing = {
      stockQty: 1500,
      stockValueMinor: undefined as unknown as number,
      unitCostMinor: 12,
      displayUnit: 'kg',
    }
    expect(shownUnitCostMinor(ing)).toBe(12_000) // cache ×factor, never NaN → no "IDRNaN"
  })
})

describe('formatShownQty', () => {
  it('shows fractional kg and whole counts', () => {
    expect(formatShownQty(1500, kg, 'en-US')).toBe('1.5')
    expect(formatShownQty(1234, kg, 'en-US')).toBe('1.234')
    expect(formatShownQty(500, pcs, 'en-US')).toBe('500')
  })
})
