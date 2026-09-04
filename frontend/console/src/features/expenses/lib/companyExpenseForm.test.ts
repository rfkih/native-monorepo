import { describe, expect, it } from 'vitest'
import {
  dateOnlyToInstant,
  inventoryLinesTotalMinor,
  parseGeneralExpense,
  parseInventoryExpense,
  parseInventoryLine,
  parsePackedQtyBase,
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
    receiptNameDiffers: false,
    receiptDescriptionInput: '',
    packSizeInput: '',
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

  describe('"Nama di nota berbeda" (receiptNameDiffers)', () => {
    it('omits description when the toggle is off, regardless of receiptDescriptionInput', () => {
      const parsed = parseInventoryLine(
        lineDraft({ receiptNameDiffers: false, receiptDescriptionInput: 'AYAM BROILER 1KG' }),
        kg,
        IDR,
      )
      expect(parsed).toEqual({
        ingredientId: 'ing-1',
        ingredientName: 'Tepung terigu',
        qtyBase: 1500,
        valueMinor: 20_000,
      })
      expect(parsed).not.toHaveProperty('description')
    })

    it('sends description when the toggle is on and the text differs from the ingredient name', () => {
      const parsed = parseInventoryLine(
        lineDraft({ receiptNameDiffers: true, receiptDescriptionInput: 'AYAM BROILER 1KG' }),
        kg,
        IDR,
      )
      expect(parsed).toEqual({
        ingredientId: 'ing-1',
        ingredientName: 'Tepung terigu',
        qtyBase: 1500,
        valueMinor: 20_000,
        description: 'AYAM BROILER 1KG',
      })
    })

    it('trims the receipt description', () => {
      const parsed = parseInventoryLine(
        lineDraft({ receiptNameDiffers: true, receiptDescriptionInput: '  Ayam fillet segar  ' }),
        kg,
        IDR,
      )
      expect(parsed?.description).toBe('Ayam fillet segar')
    })

    it('omits description when the toggle is on but the text EQUALS the ingredient name', () => {
      const parsed = parseInventoryLine(
        lineDraft({ receiptNameDiffers: true, receiptDescriptionInput: 'Tepung terigu' }),
        kg,
        IDR,
      )
      expect(parsed).not.toHaveProperty('description')
    })

    it('omits description when the toggle is on but the text only differs by whitespace/casing-neutral trim', () => {
      const parsed = parseInventoryLine(
        lineDraft({ receiptNameDiffers: true, receiptDescriptionInput: '  Tepung terigu  ' }),
        kg,
        IDR,
      )
      expect(parsed).not.toHaveProperty('description')
    })

    it('rejects the whole line when the toggle is on but the receipt text is blank', () => {
      expect(
        parseInventoryLine(lineDraft({ receiptNameDiffers: true, receiptDescriptionInput: '' }), kg, IDR),
      ).toBeNull()
      expect(
        parseInventoryLine(
          lineDraft({ receiptNameDiffers: true, receiptDescriptionInput: '   ' }),
          kg,
          IDR,
        ),
      ).toBeNull()
    })
  })

  describe('pack size ("Isi per kemasan")', () => {
    it('is unchanged when no pack size is entered', () => {
      expect(parseInventoryLine(lineDraft({ packSizeInput: '' }), kg, IDR)?.qtyBase).toBe(1500)
    })

    it('multiplies packs × per-pack qty for a base-unit ingredient (the tortilla case)', () => {
      // Receipt says "TORTILLA 1 PCS" for a pack of 20 individual tortillas.
      const parsed = parseInventoryLine(
        lineDraft({ qtyInput: '1', packSizeInput: '20' }),
        pcs,
        IDR,
      )
      expect(parsed?.qtyBase).toBe(20)
    })

    it('multiplies packs × per-pack qty for a kg-display ingredient (packs × N × 1000)', () => {
      // 2 sacks, each holding 5 kg.
      const parsed = parseInventoryLine(
        lineDraft({ qtyInput: '2', packSizeInput: '5' }),
        kg,
        IDR,
      )
      expect(parsed?.qtyBase).toBe(2 * 5 * 1000)
    })

    it('computes exactly what a typo would produce (the scale-error safety net)', () => {
      // A "200" typo instead of "20" inflates stock 10x — the math itself must be predictable so
      // the UI's inline readback can catch it before submit.
      const parsed = parseInventoryLine(lineDraft({ qtyInput: '1', packSizeInput: '200' }), pcs, IDR)
      expect(parsed?.qtyBase).toBe(200)
    })

    it('rejects a fractional pack size', () => {
      expect(parseInventoryLine(lineDraft({ qtyInput: '1', packSizeInput: '2.5' }), pcs, IDR)).toBeNull()
    })

    it('rejects a zero or negative pack size', () => {
      expect(parseInventoryLine(lineDraft({ qtyInput: '1', packSizeInput: '0' }), pcs, IDR)).toBeNull()
      expect(parseInventoryLine(lineDraft({ qtyInput: '1', packSizeInput: '-5' }), pcs, IDR)).toBeNull()
    })

    it('rejects a fractional, zero, or negative pack COUNT (qtyInput) once pack size is set', () => {
      expect(parseInventoryLine(lineDraft({ qtyInput: '1.5', packSizeInput: '20' }), pcs, IDR)).toBeNull()
      expect(parseInventoryLine(lineDraft({ qtyInput: '0', packSizeInput: '20' }), pcs, IDR)).toBeNull()
      expect(parseInventoryLine(lineDraft({ qtyInput: '-1', packSizeInput: '20' }), pcs, IDR)).toBeNull()
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
