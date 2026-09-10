import { describe, expect, it } from 'vitest'
import type { Ingredient } from '../ingredientApi'
import {
  buildRows,
  catalogTotals,
  chipCounts,
  classify,
  daysLeft,
  filterRows,
  LOW_STOCK_DAYS,
  nextCatalogSort,
  parseCatalogFilter,
  parseCatalogSort,
  rankRows,
  usageRates,
} from './catalogView'

function ing(over: Partial<Ingredient> & { id: string; name: string }): Ingredient {
  const unitCostMinor = over.unitCostMinor === undefined ? 100 : over.unitCostMinor
  const stockQty = over.stockQty ?? 1000
  return {
    businessId: 'b',
    unit: 'g',
    displayUnit: 'kg',
    active: true,
    packSize: null,
    costCurrency: unitCostMinor == null ? null : 'IDR',
    stockValueMinor: unitCostMinor == null ? 0 : stockQty * unitCostMinor,
    ...over,
    id: over.id,
    name: over.name,
    stockQty,
    unitCostMinor,
  }
}

describe('usageRates', () => {
  it('divides by the most days any ingredient moved, never by the calendar', () => {
    const { activeDays, rateById } = usageRates([
      { ingredientId: 'a', totalUsedQty: 7000, daysWithMovement: 5 },
      { ingredientId: 'b', totalUsedQty: 500, daysWithMovement: 1 },
      { ingredientId: 'c', totalUsedQty: 0, daysWithMovement: 2 },
    ])
    expect(activeDays).toBe(5)
    expect(rateById.get('a')).toBe(1400)
    // A sporadic ingredient shares the outlet's denominator — used once, spread over five open days.
    expect(rateById.get('b')).toBe(100)
    // Movement without usage (a receipt) is not a rate.
    expect(rateById.has('c')).toBe(false)
  })

  it('floors the denominator at one so an empty window cannot divide by zero', () => {
    expect(usageRates([]).activeDays).toBe(1)
    expect(
      usageRates([{ ingredientId: 'a', totalUsedQty: 10, daysWithMovement: 0 }]).rateById.get('a'),
    ).toBe(10)
  })
})

describe('daysLeft + classify', () => {
  it('reads days as stock over rate and has no answer without usage', () => {
    expect(daysLeft(8400, 2150)).toBeCloseTo(3.907, 3)
    expect(daysLeft(8400, null)).toBeNull()
    expect(daysLeft(8400, 0)).toBeNull()
  })

  it('walks the class ladder most urgent first', () => {
    expect(classify(ing({ id: 'a', name: 'a', stockQty: 0 }), null)).toBe('zero')
    expect(classify(ing({ id: 'a', name: 'a' }), LOW_STOCK_DAYS)).toBe('low')
    expect(classify(ing({ id: 'a', name: 'a' }), LOW_STOCK_DAYS + 0.1)).toBe('ok')
    expect(classify(ing({ id: 'a', name: 'a', unit: 'pack', displayUnit: null }), null)).toBe(
      'unit',
    )
    expect(classify(ing({ id: 'a', name: 'a', unitCostMinor: null }), 20)).toBe('nocost')
    // A pack ingredient that is also nearly out reads as nearly out — the shelf beats the unit.
    expect(classify(ing({ id: 'a', name: 'a', unit: 'pack', displayUnit: null }), 1)).toBe('low')
  })
})

describe('rows, filters and ranking', () => {
  const items = [
    ing({ id: 'flour', name: 'Tepung', stockQty: 25000, unitCostMinor: 12 }),
    ing({
      id: 'sauce',
      name: 'Saus',
      unit: 'pack',
      displayUnit: null,
      stockQty: 6,
      unitCostMinor: 34000,
    }),
    ing({ id: 'tomato', name: 'Tomat', stockQty: 0, unitCostMinor: 18 }),
    ing({ id: 'ice', name: 'Es batu', stockQty: 9000, unitCostMinor: null }),
    ing({ id: 'meat', name: 'Daging', stockQty: 8400, unitCostMinor: 118 }),
    ing({ id: 'lettuce', name: 'Selada', stockQty: 1800, unitCostMinor: 22 }),
  ]
  const rates = new Map([
    ['meat', 2150],
    ['lettuce', 900],
    ['flour', 100],
    ['ice', 1000],
    ['tomato', 640],
  ])
  const usedToday = new Map([
    ['meat', 2000],
    ['ice', 3000],
  ])
  const rows = buildRows(items, rates, usedToday)

  it('builds one row per ingredient with rate, today, days and class', () => {
    const meat = rows.find((r) => r.ingredient.id === 'meat')!
    expect(meat.rate).toBe(2150)
    expect(meat.usedToday).toBe(2000)
    expect(meat.days).toBeCloseTo(3.907, 3)
    expect(meat.cls).toBe('ok')
    expect(rows.find((r) => r.ingredient.id === 'lettuce')!.cls).toBe('low')
    expect(rows.find((r) => r.ingredient.id === 'sauce')!.usedToday).toBe(0)
  })

  it('counts every chip independently of the drawn class', () => {
    expect(chipCounts(rows)).toEqual({ zero: 1, low: 1, unit: 1, nocost: 1 })
    expect(filterRows(rows, 'low').map((r) => r.ingredient.id)).toEqual(['lettuce'])
    expect(filterRows(rows, 'unit').map((r) => r.ingredient.id)).toEqual(['sauce'])
    expect(filterRows(rows, null)).toHaveLength(6)
    // Zero stock is never "hampir habis" — it is already out.
    expect(filterRows(rows, 'low').some((r) => r.ingredient.id === 'tomato')).toBe(false)
  })

  it('ranks by action: class ladder, fewest days, no usage last, then name', () => {
    expect(rankRows(rows, 'action', 'id-ID').map((r) => r.ingredient.id)).toEqual([
      'tomato', // zero
      'lettuce', // low, 2 days
      'sauce', // unit
      'ice', // nocost
      'meat', // ok, 3.9 days
      'flour', // ok, 250 days
    ])
  })

  it('ranks by value with the cheapest shelf last and by name in the locale', () => {
    expect(rankRows(rows, 'value', 'id-ID').map((r) => r.ingredient.id)).toEqual([
      'meat', // 991 200
      'flour', // 300 000
      'sauce', // 204 000
      'lettuce', // 39 600
      'ice', // uncosted → 0
      'tomato', // 0
    ])
    expect(rankRows(rows, 'name', 'id-ID').map((r) => r.ingredient.name)).toEqual([
      'Daging',
      'Es batu',
      'Saus',
      'Selada',
      'Tepung',
      'Tomat',
    ])
  })

  it('does not mutate the input order', () => {
    const before = rows.map((r) => r.ingredient.id)
    rankRows(rows, 'name', 'id-ID')
    expect(rows.map((r) => r.ingredient.id)).toEqual(before)
  })

  it('totals the costed shelf and the burn, and names how many are uncosted', () => {
    const totals = catalogTotals(rows, 'IDR')
    expect(totals.totalValueMinor).toBe(300000 + 204000 + 0 + 991200 + 39600)
    expect(totals.costedCount).toBe(5)
    expect(totals.uncostedCount).toBe(1)
    // Ice is used today but has no cost, so it adds nothing to the money figure.
    expect(totals.usedTodayValueMinor).toBe(2000 * 118)
    const burn = 2150 * 118 + 900 * 22 + 100 * 12 + 640 * 18
    expect(totals.daysOverall).toBeCloseTo(totals.totalValueMinor / burn, 6)
  })

  it('has no shelf estimate when nothing costed is being used', () => {
    const still = buildRows(items, new Map(), new Map())
    expect(catalogTotals(still, 'IDR').daysOverall).toBeNull()
  })

  it('never sums two currencies — a foreign-costed item is costed but adds nothing', () => {
    const truffle = ing({
      id: 'tf',
      name: 'Truffle',
      stockQty: 900,
      unitCostMinor: 85,
      costCurrency: 'USD',
    })
    const totals = catalogTotals(buildRows([...items, truffle], rates, usedToday), 'IDR')
    expect(totals.costedCount).toBe(6)
    expect(totals.uncostedCount).toBe(1)
    expect(totals.totalValueMinor).toBe(300000 + 204000 + 0 + 991200 + 39600)
  })

  it('treats a missing stockValueMinor (older server) as zero, not NaN', () => {
    const legacy = ing({ id: 'x', name: 'x' })
    ;(legacy as { stockValueMinor?: number }).stockValueMinor = undefined
    const totals = catalogTotals(buildRows([legacy], new Map(), new Map()), 'IDR')
    expect(totals.totalValueMinor).toBe(0)
  })
})

describe('URL parsing', () => {
  it('reads known values and falls back quietly', () => {
    expect(parseCatalogFilter('low')).toBe('low')
    expect(parseCatalogFilter('bogus')).toBeNull()
    expect(parseCatalogFilter(null)).toBeNull()
    expect(parseCatalogSort('value')).toBe('value')
    expect(parseCatalogSort(undefined)).toBe('action')
    expect(nextCatalogSort('action')).toBe('value')
    expect(nextCatalogSort('name')).toBe('action')
  })
})
