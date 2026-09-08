import { describe, expect, it } from 'vitest'
import {
  ASSET_GROUPS,
  IMPOSSIBLY_NEGATIVE_ASSET_CODES,
  OTHER_ASSETS_LABEL_KEY,
  groupAssetLines,
  splitZeroLines,
  unnaturalAssetLines,
} from '../balanceSheetView'
import { ACCOUNT_LABEL_KEYS } from '../accountLabels'
import type { BalanceLine } from '../api'

const line = (accountCode: string, balanceMinor: number): BalanceLine => ({
  accountCode,
  accountType: 'ASSET',
  balanceMinor,
  currency: 'IDR',
})

/**
 * The UAT ledger as of 2026-08, in server order (`ORDER BY account_code`) — the fixture the redesign
 * was reviewed against. Inventory really is negative there (stock expensed twice), and Utang usaha
 * really does net to zero, so these rules are exercised against the shapes they were written for.
 */
const UAT_ASSETS: BalanceLine[] = [
  line('1100', -4000),
  line('1500', 9_000_000),
  line('1900', 4_517_000),
  line('1901', 10_000),
  line('1902', 10_000),
]
const UAT_TOTAL_ASSETS = 13_533_000

describe('splitZeroLines', () => {
  it('keeps every line that carries a balance and sets aside the ones that net to nothing', () => {
    const { visible, hidden } = splitZeroLines([line('2000', 0), line('2500', 5000)])
    expect(visible.map((l) => l.accountCode)).toEqual(['2500'])
    expect(hidden.map((l) => l.accountCode)).toEqual(['2000'])
  })

  it('keeps a negative balance visible — zero is the only thing worth hiding', () => {
    const { visible, hidden } = splitZeroLines([line('1100', -4000)])
    expect(visible).toHaveLength(1)
    expect(hidden).toEqual([])
  })
})

describe('groupAssetLines', () => {
  it('leads with cash rather than the lowest account code', () => {
    const groups = groupAssetLines(UAT_ASSETS)
    expect(groups[0].lines[0].accountCode).toBe('1900')
  })

  it('subtotals add up to the total assets the server reported', () => {
    const groups = groupAssetLines(UAT_ASSETS)
    expect(groups.reduce((sum, g) => sum + g.subtotalMinor, 0)).toBe(UAT_TOTAL_ASSETS)
  })

  it('drops groups with nothing in them', () => {
    const groups = groupAssetLines(UAT_ASSETS)
    expect(groups.map((g) => g.labelKey)).toEqual([
      'statements.groups.liquid',
      'statements.groups.goods',
    ])
  })

  it('never loses a line — an account no group names falls through to the catch-all', () => {
    const groups = groupAssetLines([...UAT_ASSETS, line('1777', 250)])
    const all = groups.flatMap((g) => g.lines.map((l) => l.accountCode))
    expect(all).toHaveLength(6)
    expect(groups.at(-1)).toMatchObject({ labelKey: OTHER_ASSETS_LABEL_KEY, subtotalMinor: 250 })
  })

  it('places a line in exactly one group', () => {
    const codes = groupAssetLines(UAT_ASSETS).flatMap((g) => g.lines.map((l) => l.accountCode))
    expect(new Set(codes).size).toBe(codes.length)
  })

  // Tracking by account code instead of by position would collapse these into one and break the
  // subtotal guarantee. The endpoint groups by code today; the contract does not depend on it.
  it('keeps both rows when one account code arrives twice', () => {
    const groups = groupAssetLines([line('1900', 500), line('1900', 300)])
    expect(groups).toHaveLength(1)
    expect(groups[0].lines.map((l) => l.balanceMinor)).toEqual([500, 300])
    expect(groups[0].subtotalMinor).toBe(800)
  })

  // The named groups are the whole point of the re-order; a seeded asset falling through to
  // "Lainnya" means the chart grew and the groups did not.
  it('names every seeded asset account, so nothing routine reaches the catch-all', () => {
    const grouped = new Set(ASSET_GROUPS.flatMap((g) => g.codes))
    const seededAssets = Object.keys(ACCOUNT_LABEL_KEYS).filter((code) => /^1\d{3}$/.test(code))
    expect(seededAssets.filter((code) => !grouped.has(code))).toEqual([])
  })
})

describe('unnaturalAssetLines', () => {
  it('flags stock that has gone negative — the double-expensed-purchase symptom', () => {
    expect(unnaturalAssetLines(UAT_ASSETS).map((l) => l.accountCode)).toEqual(['1100'])
  })

  it('leaves a contra-asset alone — accumulated depreciation is meant to be negative', () => {
    expect(IMPOSSIBLY_NEGATIVE_ASSET_CODES.has('1590')).toBe(false)
    expect(unnaturalAssetLines([line('1590', -750_000)])).toEqual([])
  })

  /**
   * The banner tells the reader to go and investigate, and blames a double-expensed purchase. On a
   * clearing or receivable account a negative balance is ordinary timing that fixes itself, so
   * firing there would send owners chasing healthy figures.
   */
  it('stays quiet on accounts that legitimately swing negative', () => {
    const timingNegatives = [
      line('1901', -25_000), // QRIS settled ahead of its capture
      line('1902', -25_000), // card settlement, same
      line('1200', -10_000), // customer overpayment
      line('1250', -10_000), // marketplace settlement ahead of the sale posting
      line('1000', -5_000), // an overdrawn bank account
      line('1400', -1_000), // prepayment net position
    ]
    expect(unnaturalAssetLines(timingNegatives)).toEqual([])
  })

  it('says nothing when every asset is positive', () => {
    expect(unnaturalAssetLines([line('1900', 1), line('1500', 2)])).toEqual([])
  })
})
