import { describe, expect, it } from 'vitest'
import type { IngredientStocktakeResponse } from '../ingredientStocktakeApi'
import { lastCountsFor, lineBearing, variedCount } from './stocktakeLines'

function line(ingredientId: string, systemQty: number, countedQty: number) {
  return {
    ingredientId,
    name: ingredientId,
    unit: 'g',
    systemQty,
    countedQty,
    varianceQty: countedQty - systemQty,
    unitCostMinor: 100,
    varianceValueMinor: (countedQty - systemQty) * 100,
  }
}

function stocktake(
  id: string,
  countedAt: string,
  lines: ReturnType<typeof line>[],
): IngredientStocktakeResponse {
  return { id, businessId: 'b', currency: 'IDR', countedAt, shrinkageMinor: 0, lines }
}

const history = [
  stocktake('h2', '2026-09-02T12:05:00Z', [line('meat', 11200, 10600), line('cheese', 2400, 2400)]),
  stocktake('h1', '2026-09-09T11:40:00Z', [line('meat', 9800, 8400)]),
  stocktake('h3', '2026-08-26T11:52:00Z', [line('meat', 7400, 7400), line('cheese', 3000, 2900)]),
  stocktake('h4', '2026-08-19T12:10:00Z', [line('meat', 5000, 4900)]),
]

describe('variedCount', () => {
  it('counts the lines that disagreed with the system', () => {
    expect(variedCount(history[0])).toBe(1)
    expect(variedCount(history[2])).toBe(1)
    expect(variedCount({ lines: [] })).toBe(0)
  })
})

describe('lastCountsFor', () => {
  it('cuts the history by ingredient, newest first, capped', () => {
    const counts = lastCountsFor(history, 'meat')
    expect(counts.map((c) => c.stocktakeId)).toEqual(['h1', 'h2', 'h3'])
    expect(counts[0].line.countedQty).toBe(8400)
    expect(counts[0].currency).toBe('IDR')
    expect(lastCountsFor(history, 'meat', 1).map((c) => c.stocktakeId)).toEqual(['h1'])
  })

  it('skips stocktakes the ingredient was not part of', () => {
    expect(lastCountsFor(history, 'cheese').map((c) => c.stocktakeId)).toEqual(['h2', 'h3'])
    expect(lastCountsFor(history, 'nothing')).toEqual([])
  })
})

describe('lineBearing', () => {
  const byId = new Map([
    ['meat', { unit: 'g', displayUnit: 'kg' }],
    ['sauce', { unit: 'g', displayUnit: null }],
  ])

  it('formats through the catalog while the base unit still matches', () => {
    expect(lineBearing({ ingredientId: 'meat', unit: 'g' }, byId)).toEqual({
      unit: 'g',
      displayUnit: 'kg',
    })
  })

  it('keeps the unit a line was counted in after a conversion, and for a removed ingredient', () => {
    // convert-unit rescales recipes and the ledger, never past stocktake lines: 3 pack stays 3 pack.
    expect(lineBearing({ ingredientId: 'sauce', unit: 'pack' }, byId)).toEqual({
      unit: 'pack',
      displayUnit: null,
    })
    expect(lineBearing({ ingredientId: 'gone', unit: 'ml' }, byId)).toEqual({
      unit: 'ml',
      displayUnit: null,
    })
  })
})
