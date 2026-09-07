import { describe, expect, it } from 'vitest'
import { summarizeStocktakeDraft, type StocktakeDraftLine } from '../stocktakeDraft'

/** A costed line in the company's base currency unless told otherwise. */
function line(over: Partial<StocktakeDraftLine> = {}): StocktakeDraftLine {
  return { systemQty: 100, countedQty: 100, unitCostMinor: 50, currency: 'IDR', ...over }
}

describe('summarizeStocktakeDraft', () => {
  it('counts nothing when every count still equals the system quantity', () => {
    const s = summarizeStocktakeDraft([line(), line(), line()], 'IDR')
    expect(s).toEqual({ changed: 0, invalid: 0, netValueMinor: 0, partialValue: false })
  })

  it('values a shortage NEGATIVE and an overage positive', () => {
    const short = summarizeStocktakeDraft([line({ countedQty: 90 })], 'IDR')
    expect(short.changed).toBe(1)
    expect(short.netValueMinor).toBe(-500) // −10 × 50

    const over = summarizeStocktakeDraft([line({ countedQty: 110 })], 'IDR')
    expect(over.netValueMinor).toBe(500)
  })

  it('nets a shortage against an overage', () => {
    const s = summarizeStocktakeDraft([line({ countedQty: 90 }), line({ countedQty: 130 })], 'IDR')
    expect(s.changed).toBe(2)
    expect(s.netValueMinor).toBe(1000) // (−10 + 30) × 50
  })

  it('counts an uncosted difference as changed but worth nothing', () => {
    const s = summarizeStocktakeDraft([line({ countedQty: 40, unitCostMinor: null })], 'IDR')
    expect(s.changed).toBe(1)
    expect(s.netValueMinor).toBe(0)
    expect(s.partialValue).toBe(false)
  })

  it('flags — never silently mixes — a line priced in another currency', () => {
    const s = summarizeStocktakeDraft(
      [line({ countedQty: 90 }), line({ countedQty: 50, currency: 'USD' })],
      'IDR',
    )
    expect(s.changed).toBe(2)
    expect(s.netValueMinor).toBe(-500) // the IDR line only
    expect(s.partialValue).toBe(true)
  })

  it('counts an unparseable line as invalid and keeps it out of the value', () => {
    const s = summarizeStocktakeDraft([line({ countedQty: null }), line({ countedQty: 90 })], 'IDR')
    expect(s.invalid).toBe(1)
    expect(s.changed).toBe(1)
    expect(s.netValueMinor).toBe(-500)
  })

  it('is empty for an empty draft', () => {
    expect(summarizeStocktakeDraft([], 'IDR')).toEqual({
      changed: 0,
      invalid: 0,
      netValueMinor: 0,
      partialValue: false,
    })
  })
})
