import { describe, expect, it } from 'vitest'
import { checkStocktakeVariance, type StocktakeVarianceLine } from '../stocktakeVarianceGuard'

/** A plausible base line every test tweaks — a small everyday ingredient count. */
function line(overrides: Partial<StocktakeVarianceLine>): StocktakeVarianceLine {
  return {
    ingredientId: 'ing-1',
    systemQty: 3_411,
    countedQty: 3_411,
    unitCostMinor: 61,
    currency: 'IDR',
    ...overrides,
  }
}

describe('checkStocktakeVariance', () => {
  it('an exact match never flags', () => {
    expect(checkStocktakeVariance([line({})])).toEqual([])
  })

  it('normal shrinkage/restock — small ratio, small value — never flags', () => {
    // ~6% under, well inside ordinary usage.
    const flags = checkStocktakeVariance([line({ countedQty: 3_200 })])
    expect(flags).toEqual([])
  })

  it('the real incident: 3,411 g system vs 2,640,000 g counted (kg→g slip) trips BOTH wires', () => {
    const flags = checkStocktakeVariance([
      line({ ingredientId: 'daging-kebab', systemQty: 3_411, countedQty: 2_640_000, unitCostMinor: 61 }),
    ])
    expect(flags).toHaveLength(1)
    expect(flags[0]).toMatchObject({ ingredientId: 'daging-kebab', systemQty: 3_411, countedQty: 2_640_000 })
    expect(flags[0].reasons).toContain('ratio')
    expect(flags[0].reasons).toContain('value')
  })

  it('mirrored slip — counted 1000× too SMALL — also trips the ratio wire', () => {
    const flags = checkStocktakeVariance([line({ systemQty: 2_500_000, countedQty: 2_500, unitCostMinor: null })])
    expect(flags).toHaveLength(1)
    expect(flags[0].reasons).toEqual(['ratio'])
  })

  it('a big ratio on a near-empty item (small absolute gap) does NOT flag', () => {
    // 200 / 5 = 40x is under IMPLAUSIBLE_RATIO already, but even bumped past 100x the gap is tiny.
    const flags = checkStocktakeVariance([
      line({ systemQty: 2, countedQty: 250, unitCostMinor: null }), // 125x ratio, delta 248
    ])
    expect(flags).toEqual([])
  })

  it('an ordinary big top-up of a near-empty item (ratio trips, absolute gap tiny) does not flag', () => {
    const flags = checkStocktakeVariance([line({ systemQty: 5, countedQty: 200, unitCostMinor: null })])
    expect(flags).toEqual([])
  })

  it('a first count of a brand-new ingredient (systemQty 0) never trips the ratio wire', () => {
    const flags = checkStocktakeVariance([line({ systemQty: 0, countedQty: 5_000_000, unitCostMinor: null })])
    expect(flags).toEqual([])
  })

  it('but a brand-new ingredient DOES trip the value wire when costed and the implied value is huge', () => {
    const flags = checkStocktakeVariance([line({ systemQty: 0, countedQty: 200_000, unitCostMinor: 61 })])
    expect(flags).toHaveLength(1)
    expect(flags[0].reasons).toEqual(['value'])
  })

  it('a physical count of exactly zero against a large system never trips ratio (uncosted)', () => {
    const flags = checkStocktakeVariance([line({ systemQty: 3_000_000, countedQty: 0, unitCostMinor: null })])
    expect(flags).toEqual([])
  })

  it('a moderate ratio on an EXPENSIVE ingredient trips the value wire alone', () => {
    // 1.5x ratio (never trips ratio), but 50 units x Rp 200,000 = Rp 10,000,000 > the 5,000,000 threshold.
    const flags = checkStocktakeVariance([
      line({ systemQty: 100, countedQty: 150, unitCostMinor: 200_000 }),
    ])
    expect(flags).toHaveLength(1)
    expect(flags[0].reasons).toEqual(['value'])
  })

  it('an uncosted line can only ever trip the ratio wire, never value', () => {
    const flags = checkStocktakeVariance([line({ countedQty: 4_000_000, unitCostMinor: null })])
    expect(flags).toHaveLength(1)
    expect(flags[0].reasons).toEqual(['ratio'])
  })

  it('the value threshold is currency-scaled (USD cents, not IDR-sized)', () => {
    // $1,000 variance value in USD (2 minor-unit exponent) clears the $500 USD threshold.
    const overThreshold = checkStocktakeVariance([
      line({ systemQty: 100, countedQty: 110, unitCostMinor: 10_000, currency: 'USD' }), // delta 10 x $100.00 = $1,000
    ])
    expect(overThreshold[0]?.reasons).toEqual(['value'])

    // The same raw numbers stay under an unlisted currency's default $500-equivalent threshold once
    // scaled down — a delta this small, at this unit cost, in a currency without a bespoke entry.
    const underThreshold = checkStocktakeVariance([
      line({ systemQty: 100, countedQty: 102, unitCostMinor: 10_000, currency: 'SGD' }), // delta 2 x $100.00 = $200
    ])
    expect(underThreshold).toEqual([])
  })

  it('only the tripped lines are returned — untripped lines are omitted entirely', () => {
    const flags = checkStocktakeVariance([
      line({ ingredientId: 'ok-1', countedQty: 3_400 }),
      line({ ingredientId: 'bad-1', countedQty: 3_000_000 }),
      line({ ingredientId: 'ok-2', countedQty: 3_450 }),
    ])
    expect(flags.map((f) => f.ingredientId)).toEqual(['bad-1'])
  })

  it('an empty submission flags nothing', () => {
    expect(checkStocktakeVariance([])).toEqual([])
  })
})
