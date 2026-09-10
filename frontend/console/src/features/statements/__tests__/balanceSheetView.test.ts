import { describe, expect, it } from 'vitest'
import {
  ASSET_GROUPS,
  IMPOSSIBLY_NEGATIVE_ASSET_CODES,
  OTHER_ASSETS_LABEL_KEY,
  RETAINED_EARNINGS_ACCOUNT,
  displayBalanceSheet,
  groupAssetLines,
  netFixedAssetLines,
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

describe('netFixedAssetLines', () => {
  it('shows equipment at what it is worth — cost less the depreciation booked against it', () => {
    const netted = netFixedAssetLines([line('1500', 9_000_000), line('1590', -2_150_000)])
    expect(netted).toHaveLength(1)
    expect(netted[0]).toMatchObject({ accountCode: '1500', balanceMinor: 6_850_000 })
  })

  it('does not change what the assets add up to', () => {
    const raw = [line('1900', 4_517_000), line('1500', 9_000_000), line('1590', -2_150_000)]
    const sum = (ls: BalanceLine[]) => ls.reduce((t, l) => t + l.balanceMinor, 0)
    expect(sum(netFixedAssetLines(raw))).toBe(sum(raw))
  })

  it('leaves depreciation visible when there is no cost line to fold it into', () => {
    const netted = netFixedAssetLines([line('1590', -500_000)])
    expect(netted.map((l) => l.accountCode)).toEqual(['1590'])
  })

  it('leaves a cost line alone when nothing has been depreciated yet', () => {
    const netted = netFixedAssetLines([line('1500', 9_000_000)])
    expect(netted).toEqual([line('1500', 9_000_000)])
  })

  // Netting runs before the flag check, so the warning follows the figure actually on screen.
  it('flags equipment whose depreciation has eaten past its cost', () => {
    const netted = netFixedAssetLines([line('1500', 1_000_000), line('1590', -1_200_000)])
    expect(unnaturalAssetLines(netted).map((l) => l.accountCode)).toEqual(['1500'])
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

describe('displayBalanceSheet — the one display step the desktop page and the phone screen share', () => {
  const typed = (accountType: string) => (accountCode: string, balanceMinor: number): BalanceLine => ({
    ...line(accountCode, balanceMinor),
    accountType,
  })
  const liability = typed('LIABILITY')
  const equity = typed('EQUITY')
  const data = {
    // Inventory impossibly negative; equipment fully depreciated (nets to zero); a zero VAT row.
    assetLines: [line('1000', 5_000), line('1100', -4_000), line('1300', 0), line('1500', 9_000), line('1590', -9_000)],
    // Utang usaha nets to zero — a real UAT shape.
    liabilityLines: [liability('2000', 0), liability('2200', 1_200)],
    equityLines: [equity('3000', 10_000), equity(RETAINED_EARNINGS_ACCOUNT, -3_200)],
  }
  const flat = (v: ReturnType<typeof displayBalanceSheet>) => v.assetGroups.flatMap((g) => g.lines)

  it('flags impossible balances on ASSET rows only, never on a colliding liability or equity code', () => {
    const view = displayBalanceSheet(data, { showZeros: false })
    expect(view.flagged.map((l) => l.accountCode)).toEqual(['1100'])
    expect(flat(view).find((l) => l.accountCode === '1100')?.flagged).toBe(true)
    expect(view.liabilityLines.every((l) => !l.flagged)).toBe(true)
    expect(view.equityLines.every((l) => !l.flagged)).toBe(true)
  })

  it('keeps zero rows in the output as print-only, except the netted equipment row', () => {
    const view = displayBalanceSheet(data, { showZeros: false })
    const byCode = Object.fromEntries(flat(view).map((l) => [l.accountCode, l]))
    expect(byCode['1300'].printOnly).toBe(true)
    // Cost and depreciation land on one row worth nothing — still owned, so still on screen.
    expect(byCode['1500']).toMatchObject({ amountMinor: 0, printOnly: false })
    expect(byCode['1590']).toBeUndefined()
    expect(view.liabilityLines.find((l) => l.accountCode === '2000')?.printOnly).toBe(true)
    expect(view.hiddenAssets).toBe(2)
    expect(view.hiddenLiabilities).toBe(1)
    expect(view.hiddenEquity).toBe(0)
  })

  it('shows every row when zeros are revealed; the hidden counts still say how many there were', () => {
    const view = displayBalanceSheet(data, { showZeros: true })
    expect([...flat(view), ...view.liabilityLines, ...view.equityLines].every((l) => !l.printOnly)).toBe(true)
    expect(view.hiddenAssets).toBe(2)
  })

  it('names the synthetic profit row instead of showing its code', () => {
    const view = displayBalanceSheet(data, { showZeros: false })
    const profit = view.equityLines[1]
    expect(profit.accountCode).toBe('')
    expect(profit.labelKey).toBe(ACCOUNT_LABEL_KEYS[RETAINED_EARNINGS_ACCOUNT])
    expect(profit.amountMinor).toBe(-3_200)
    expect(view.equityLines[0].accountCode).toBe('3000')
    expect(view.equityLines[0].labelKey).toBeUndefined()
  })

  it('groups assets by liquidity with the group label keys the page translates', () => {
    const view = displayBalanceSheet(data, { showZeros: false })
    expect(view.assetGroups.map((g) => g.labelKey)).toEqual(ASSET_GROUPS.filter((g) => g.codes.some((c) => ['1000', '1100', '1300', '1500'].includes(c))).map((g) => g.labelKey))
  })
})
