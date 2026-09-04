import { describe, expect, it } from 'vitest'
import {
  dateOnlyToInstant,
  inventoryLinesTotalMinor,
  parseGeneralExpense,
  parseInventoryExpense,
  parseInventoryLine,
  type GeneralExpenseDraft,
  type InventoryLineDraft,
} from './companyExpenseForm'

const IDR = 'IDR'
const USD = 'USD'

const kg = { unit: 'g', displayUnit: 'kg' }
const pcs = { unit: 'pcs', displayUnit: null }

function generalDraft(overrides: Partial<GeneralExpenseDraft> = {}): GeneralExpenseDraft {
  return {
    businessId: 'outlet-1',
    glHint: 'utilities',
    description: 'Listrik bulan ini',
    amountInput: '150000',
    occurredAt: '',
    ...overrides,
  }
}

function lineDraft(overrides: Partial<InventoryLineDraft> = {}): InventoryLineDraft {
  return {
    key: 'line-1',
    ingredientId: 'ing-1',
    ingredientName: 'Tepung terigu',
    qtyInput: '1.5',
    totalInput: '20000',
    ...overrides,
  }
}

describe('parseGeneralExpense', () => {
  it('parses a complete GENERAL draft into minor units', () => {
    expect(parseGeneralExpense(generalDraft(), IDR)).toEqual({
      businessId: 'outlet-1',
      glHint: 'utilities',
      description: 'Listrik bulan ini',
      amountMinor: 150_000,
      occurredAt: undefined,
    })
  })

  it('scales by the currency exponent (USD cents)', () => {
    expect(parseGeneralExpense(generalDraft({ amountInput: '12.50' }), USD)?.amountMinor).toBe(1250)
  })

  it('trims the description', () => {
    expect(parseGeneralExpense(generalDraft({ description: '  Listrik  ' }), IDR)?.description).toBe(
      'Listrik',
    )
  })

  it('rejects a missing outlet', () => {
    expect(parseGeneralExpense(generalDraft({ businessId: '' }), IDR)).toBeNull()
  })

  it('rejects a blank description', () => {
    expect(parseGeneralExpense(generalDraft({ description: '   ' }), IDR)).toBeNull()
  })

  it('rejects a zero, blank, or negative amount', () => {
    expect(parseGeneralExpense(generalDraft({ amountInput: '' }), IDR)).toBeNull()
    expect(parseGeneralExpense(generalDraft({ amountInput: '0' }), IDR)).toBeNull()
    expect(parseGeneralExpense(generalDraft({ amountInput: '-5000' }), IDR)).toBeNull()
  })

  it('carries a chosen date-only input through as an ISO instant', () => {
    const parsed = parseGeneralExpense(generalDraft({ occurredAt: '2026-09-01' }), IDR)
    expect(parsed?.occurredAt).toBe(new Date('2026-09-01T00:00:00').toISOString())
  })
})

describe('parseInventoryLine', () => {
  it('converts a display-unit quantity (kg) to the base integer (g) and parses the total', () => {
    expect(parseInventoryLine(lineDraft(), kg, IDR)).toEqual({
      ingredientId: 'ing-1',
      ingredientName: 'Tepung terigu',
      qtyBase: 1500,
      valueMinor: 20_000,
    })
  })

  it('accepts a whole quantity for a base-unit ingredient (pcs)', () => {
    expect(parseInventoryLine(lineDraft({ qtyInput: '12' }), pcs, IDR)).toEqual({
      ingredientId: 'ing-1',
      ingredientName: 'Tepung terigu',
      qtyBase: 12,
      valueMinor: 20_000,
    })
  })

  it('rejects a fractional quantity for a base-unit ingredient (mirrors parseShownQtyInput)', () => {
    expect(parseInventoryLine(lineDraft({ qtyInput: '1.5' }), pcs, IDR)).toBeNull()
  })

  it('rejects no ingredient chosen', () => {
    expect(parseInventoryLine(lineDraft({ ingredientId: '' }), kg, IDR)).toBeNull()
  })

  it('rejects an unresolved ingredient (stale id, e.g. deactivated after being picked)', () => {
    expect(parseInventoryLine(lineDraft(), null, IDR)).toBeNull()
  })

  it('rejects a zero/blank quantity', () => {
    expect(parseInventoryLine(lineDraft({ qtyInput: '' }), kg, IDR)).toBeNull()
    expect(parseInventoryLine(lineDraft({ qtyInput: '0' }), kg, IDR)).toBeNull()
  })

  it('rejects a zero/blank/negative total paid', () => {
    expect(parseInventoryLine(lineDraft({ totalInput: '' }), kg, IDR)).toBeNull()
    expect(parseInventoryLine(lineDraft({ totalInput: '0' }), kg, IDR)).toBeNull()
    expect(parseInventoryLine(lineDraft({ totalInput: '-1' }), kg, IDR)).toBeNull()
  })
})

describe('parseInventoryExpense', () => {
  const ingredientOf = (id: string) => (id === 'ing-1' ? kg : id === 'ing-2' ? pcs : null)

  it('parses a complete draft with multiple valid lines', () => {
    const drafts = [lineDraft(), lineDraft({ key: 'line-2', ingredientId: 'ing-2', qtyInput: '3', totalInput: '9000' })]
    const parsed = parseInventoryExpense('outlet-1', ' Belanja bahan ', '', drafts, ingredientOf, IDR)
    expect(parsed).toEqual({
      businessId: 'outlet-1',
      description: 'Belanja bahan',
      occurredAt: undefined,
      lines: [
        { ingredientId: 'ing-1', ingredientName: 'Tepung terigu', qtyBase: 1500, valueMinor: 20_000 },
        { ingredientId: 'ing-2', ingredientName: 'Tepung terigu', qtyBase: 3, valueMinor: 9000 },
      ],
    })
  })

  it('rejects a missing outlet, blank description, or zero lines', () => {
    expect(parseInventoryExpense('', 'x', '', [lineDraft()], ingredientOf, IDR)).toBeNull()
    expect(parseInventoryExpense('outlet-1', '  ', '', [lineDraft()], ingredientOf, IDR)).toBeNull()
    expect(parseInventoryExpense('outlet-1', 'x', '', [], ingredientOf, IDR)).toBeNull()
  })

  it('blocks the whole submit when ANY line is invalid — never silently drops a row', () => {
    const drafts = [lineDraft(), lineDraft({ key: 'line-2', qtyInput: '' })]
    expect(parseInventoryExpense('outlet-1', 'x', '', drafts, ingredientOf, IDR)).toBeNull()
  })
})

describe('inventoryLinesTotalMinor', () => {
  const ingredientOf = (id: string) => (id === 'ing-1' ? kg : null)

  it('sums only the lines that currently parse', () => {
    const drafts = [lineDraft(), lineDraft({ key: 'line-2', qtyInput: '' })]
    expect(inventoryLinesTotalMinor(drafts, ingredientOf, IDR)).toBe(20_000)
  })

  it('is 0 for no lines', () => {
    expect(inventoryLinesTotalMinor([], ingredientOf, IDR)).toBe(0)
  })
})

describe('dateOnlyToInstant', () => {
  it('is undefined for a blank input', () => {
    expect(dateOnlyToInstant('')).toBeUndefined()
    expect(dateOnlyToInstant('   ')).toBeUndefined()
  })

  it('converts a YYYY-MM-DD input to local-midnight ISO', () => {
    expect(dateOnlyToInstant('2026-09-01')).toBe(new Date('2026-09-01T00:00:00').toISOString())
  })

  it('is undefined for an unparseable input', () => {
    expect(dateOnlyToInstant('not-a-date')).toBeUndefined()
  })
})
