import { describe, expect, it } from 'vitest'
import {
  avgBill,
  dayKeyOffset,
  figuresFor,
  grossMargin,
  initials,
  jakartaDayBounds,
  lowStockNames,
  mergeDaily,
  mergeTopItems,
  outletShares,
  periodToClose,
  weekBars,
  weekWindow,
  weekdayDelta,
  type DailySalesRow,
  type DayFigures,
} from '../lib/todayView'

const row = (over: Partial<DailySalesRow> & { businessDate: string }): DailySalesRow => ({
  transactionCount: 1,
  netSalesMinor: 10_000,
  cogsMinor: null,
  costedTransactionCount: 0,
  currency: 'IDR',
  usesIllustrativeRules: false,
  ...over,
})

const day = (over: Partial<DayFigures> = {}): DayFigures => ({
  net: 0,
  txn: 0,
  cogs: null,
  costed: 0,
  illustrative: false,
  ...over,
})

describe('the seven-day window', () => {
  it('ends today, oldest first, across a month boundary', () => {
    expect(weekWindow('2026-09-02')).toEqual([
      '2026-08-27',
      '2026-08-28',
      '2026-08-29',
      '2026-08-30',
      '2026-08-31',
      '2026-09-01',
      '2026-09-02',
    ])
  })

  it('steps whole days either way, leap day included', () => {
    expect(dayKeyOffset('2028-03-01', -1)).toBe('2028-02-29')
    expect(dayKeyOffset('2026-12-31', 1)).toBe('2027-01-01')
  })

  it('a Jakarta day is [midnight WIB, next midnight WIB) as instants', () => {
    expect(jakartaDayBounds('2026-09-10')).toEqual({
      from: '2026-09-09T17:00:00.000Z',
      to: '2026-09-10T17:00:00.000Z',
    })
  })
})

describe('mergeDaily — outlets fold into one company day', () => {
  it('sums net, count and cogs per day; cogs stays null until one outlet is costed', () => {
    const series = mergeDaily([
      [
        row({ businessDate: '2026-09-09', netSalesMinor: 100, transactionCount: 2 }),
        row({ businessDate: '2026-09-10', netSalesMinor: 50, transactionCount: 1 }),
      ],
      [
        row({
          businessDate: '2026-09-10',
          netSalesMinor: 70,
          transactionCount: 3,
          cogsMinor: 30,
          costedTransactionCount: 2,
          usesIllustrativeRules: true,
        }),
      ],
    ])
    expect(series.get('2026-09-09')).toEqual(day({ net: 100, txn: 2 }))
    expect(series.get('2026-09-10')).toEqual(
      day({ net: 120, txn: 4, cogs: 30, costed: 2, illustrative: true }),
    )
  })

  it('a day nobody sold on reads as zeros, not undefined', () => {
    expect(figuresFor(mergeDaily([]), '2026-09-10')).toEqual(day())
  })

  it('a null list (an outlet still loading) is skipped, not a crash', () => {
    const series = mergeDaily([null, [row({ businessDate: '2026-09-10', netSalesMinor: 5 })]])
    expect(figuresFor(series, '2026-09-10').net).toBe(5)
  })
})

describe('weekBars — the hero chart', () => {
  it('scales to the tallest day and marks the last key as today', () => {
    const series = mergeDaily([
      [
        row({ businessDate: '2026-09-08', netSalesMinor: 50 }),
        row({ businessDate: '2026-09-10', netSalesMinor: 200 }),
      ],
    ])
    const bars = weekBars(series, ['2026-09-08', '2026-09-09', '2026-09-10'])
    expect(bars.map((b) => b.pct)).toEqual([25, 0, 100])
    expect(bars.map((b) => b.isToday)).toEqual([false, false, true])
  })

  it('an all-zero week draws flat bars, never NaN', () => {
    const bars = weekBars(mergeDaily([]), ['2026-09-09', '2026-09-10'])
    expect(bars.every((b) => b.pct === 0)).toBe(true)
  })

  it('a refund-only (negative) day draws as zero height', () => {
    const series = mergeDaily([[row({ businessDate: '2026-09-09', netSalesMinor: -40 })]])
    expect(weekBars(series, ['2026-09-09', '2026-09-10'])[0].pct).toBe(0)
  })
})

describe('weekdayDelta — today against the same weekday last week', () => {
  it('reports the money, count and percentage change', () => {
    const series = mergeDaily([
      [
        row({ businessDate: '2026-09-03', netSalesMinor: 100_000, transactionCount: 10 }),
        row({ businessDate: '2026-09-10', netSalesMinor: 125_000, transactionCount: 12 }),
      ],
    ])
    const d = weekdayDelta(series, '2026-09-10')
    expect(d).not.toBeNull()
    expect(d!.lastKey).toBe('2026-09-03')
    expect(d!.net).toBe(25_000)
    expect(d!.txn).toBe(2)
    expect(d!.netPct).toBeCloseTo(0.25)
    // avg bill went from 10,000 to 10,416.67 → +4.17%
    expect(d!.avgPct).toBeCloseTo(0.0417, 3)
  })

  it('is null when last week has nothing to compare against', () => {
    const series = mergeDaily([[row({ businessDate: '2026-09-10', netSalesMinor: 5 })]])
    expect(weekdayDelta(series, '2026-09-10')).toBeNull()
  })

  it('avgPct is null when either day has no transactions', () => {
    const series = mergeDaily([
      [
        row({ businessDate: '2026-09-03', netSalesMinor: 100, transactionCount: 4 }),
        row({ businessDate: '2026-09-10', netSalesMinor: -20, transactionCount: 0 }),
      ],
    ])
    expect(weekdayDelta(series, '2026-09-10')!.avgPct).toBeNull()
  })
})

describe('avgBill and grossMargin', () => {
  it('average bill is net over count, null with no transactions', () => {
    expect(avgBill(day({ net: 90_000, txn: 4 }))).toBe(22_500)
    expect(avgBill(day())).toBeNull()
  })

  it('margin needs at least one costed sale and a positive net; partial when not every sale was costed', () => {
    expect(grossMargin(day({ net: 100_000, txn: 4, cogs: 40_000, costed: 4 }))).toEqual({
      ratio: 0.6,
      partial: false,
    })
    expect(grossMargin(day({ net: 100_000, txn: 4, cogs: 10_000, costed: 1 }))).toEqual({
      ratio: 0.9,
      partial: true,
    })
    expect(grossMargin(day({ net: 100_000, txn: 4 }))).toBeNull()
    expect(grossMargin(day({ net: 0, txn: 1, cogs: 5, costed: 1 }))).toBeNull()
  })
})

describe('mergeTopItems — the same dish across outlets is one row', () => {
  it('merges by name, sums quantity and revenue, ranks by quantity', () => {
    const top = mergeTopItems(
      [
        [
          { menuItemId: 'a1', name: 'Es Teh Manis', soldQty: 100, revenueMinor: 500_000 },
          { menuItemId: 'a2', name: 'Ayam Bakar', soldQty: 40, revenueMinor: 1_200_000 },
        ],
        [
          { menuItemId: 'b1', name: 'es teh manis ', soldQty: 30, revenueMinor: 150_000 },
          { menuItemId: 'b3', name: 'Nasi Goreng', soldQty: 60, revenueMinor: 900_000 },
        ],
        null,
      ],
      3,
    )
    expect(top.map((t) => [t.name, t.soldQty, t.revenueMinor])).toEqual([
      ['Es Teh Manis', 130, 650_000],
      ['Nasi Goreng', 60, 900_000],
      ['Ayam Bakar', 40, 1_200_000],
    ])
    expect(top.map((t) => t.rank)).toEqual([1, 2, 3])
  })

  it('an empty day is an empty list', () => {
    expect(mergeTopItems([[], null])).toEqual([])
  })
})

describe('outletShares — bars against the busiest outlet', () => {
  it('sorts by net descending and scales to the top outlet', () => {
    const shares = outletShares([
      { id: 'c', name: 'Cipete', net: 25 },
      { id: 'k', name: 'Kemang', net: 100 },
      { id: 's', name: 'Senopati', net: 50 },
    ])
    expect(shares.map((s) => s.name)).toEqual(['Kemang', 'Senopati', 'Cipete'])
    expect(shares.map((s) => s.pct)).toEqual([100, 50, 25])
  })

  it('a quiet company draws every bar at zero', () => {
    expect(outletShares([{ id: 'k', name: 'Kemang', net: 0 }])[0].pct).toBe(0)
  })
})

describe('periodToClose — the current period is open until it is in the history', () => {
  it('names the current period when it has not been closed', () => {
    expect(periodToClose(['2026-07', '2026-08'], '2026-09')).toBe('2026-09')
    expect(periodToClose([], '2026-01')).toBe('2026-01')
  })

  it('is null once the current period is closed', () => {
    expect(periodToClose(['2026-08', '2026-09'], '2026-09')).toBeNull()
  })
})

describe('lowStockNames — the row that names what is running out', () => {
  const ingredient = (name: string, stockQty: number, unitCostMinor: number | null = 1) =>
    ({ id: name, name, unit: 'g', displayUnit: 'g', stockQty, unitCostMinor }) as never

  it('counts zero and low rows, zero first, then fewest days, and caps the names', () => {
    const rows = [
      { ingredient: ingredient('Gula', 5000), rate: 1000, usedToday: 0, days: 5, cls: 'ok' },
      { ingredient: ingredient('Susu UHT', 2000), rate: 1000, usedToday: 0, days: 2, cls: 'low' },
      { ingredient: ingredient('Ayam fillet', 0), rate: 500, usedToday: 0, days: 0, cls: 'zero' },
      { ingredient: ingredient('Telur', 500), rate: 1000, usedToday: 0, days: 0.5, cls: 'low' },
      { ingredient: ingredient('Kopi', 100), rate: 100, usedToday: 0, days: 1, cls: 'low' },
    ] as never[]
    expect(lowStockNames(rows, 3)).toEqual({
      count: 4,
      names: ['Ayam fillet', 'Telur', 'Kopi'],
    })
  })

  it('nothing low is a zero count with no names', () => {
    expect(lowStockNames([], 3)).toEqual({ count: 0, names: [] })
  })
})

describe('initials — the avatar monogram', () => {
  it('takes the first letter of the first two words, upper-cased', () => {
    expect(initials('Warung Kemang')).toBe('WK')
    expect(initials('kopi')).toBe('K')
    expect(initials('  PT   Maju Jaya Abadi ')).toBe('PM')
    expect(initials('')).toBe('')
  })
})
