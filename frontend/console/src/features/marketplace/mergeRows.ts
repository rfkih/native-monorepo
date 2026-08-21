/**
 * mergeMarketplaceRows — pure join of the marketplace report's three sources (ONLINE sales per
 * channel, settlements per channel, current outstanding per channel) by `channelCode` — no React,
 * no network, so it is independently unit-tested, mirroring `features/platform/amount.ts`'s
 * pure-helper idiom.
 *
 * A channelCode need not appear in all three sources (e.g. a channel settled this period with no
 * NEW online sales, or a channel with an outstanding balance carried from a prior period but no
 * activity this period) — every such row is KEPT, with the missing source's columns reading zero,
 * never dropped.
 */
import type { PlatformOutstanding } from '@/features/platform/platformApi'
import type { ChannelSalesSummary, ChannelSettlementSummary } from './marketplaceApi'

/** One joined report row — one per distinct channelCode across the three sources. */
export interface MarketplaceRow {
  channelCode: string
  currency: string
  grossSalesMinor: number
  transactionCount: number
  feeMinor: number
  netSettledMinor: number
  settlementCount: number
  outstandingMinor: number
}

/** {@link MarketplaceRow} minus the row identity — the shape of a totals/footer row. */
export type MarketplaceTotals = Omit<MarketplaceRow, 'channelCode'>

function emptyTotals(currency: string): MarketplaceTotals {
  return {
    currency,
    grossSalesMinor: 0,
    transactionCount: 0,
    feeMinor: 0,
    netSettledMinor: 0,
    settlementCount: 0,
    outstandingMinor: 0,
  }
}

function emptyRow(channelCode: string, currency: string): MarketplaceRow {
  return { channelCode, ...emptyTotals(currency) }
}

/**
 * Joins the sales, settlement, and outstanding lists into one row per channelCode (the union of
 * every channelCode seen across all three). `fallbackCurrency` (the company's base currency) is
 * used only when a channelCode's OWN sources never carry a currency at all — in practice every
 * source reports the company's single base currency, so this is a defensive floor, not the normal
 * path. Rows are sorted by channelCode for a stable, deterministic render order (mirrors
 * `features/platform/PlatformSettlements.tsx`'s `channelCodesOf`).
 */
export function mergeMarketplaceRows(
  sales: ChannelSalesSummary[],
  settlements: ChannelSettlementSummary[],
  outstanding: PlatformOutstanding[],
  fallbackCurrency: string,
): MarketplaceRow[] {
  const byCode = new Map<string, MarketplaceRow>()

  function rowFor(channelCode: string, currency: string): MarketplaceRow {
    let row = byCode.get(channelCode)
    if (!row) {
      row = emptyRow(channelCode, currency || fallbackCurrency)
      byCode.set(channelCode, row)
    }
    return row
  }

  for (const s of sales) {
    const row = rowFor(s.channelCode, s.currency)
    row.grossSalesMinor += s.grossSalesMinor
    row.transactionCount += s.transactionCount
  }
  for (const s of settlements) {
    const row = rowFor(s.channelCode, s.currency)
    row.feeMinor += s.feeMinor
    row.netSettledMinor += s.netMinor
    row.settlementCount += s.settlementCount
  }
  for (const o of outstanding) {
    const row = rowFor(o.channelCode, o.currency)
    row.outstandingMinor += o.outstandingMinor
  }

  return Array.from(byCode.values()).sort((a, b) => a.channelCode.localeCompare(b.channelCode))
}

/**
 * Column totals across every row — the report's footer total row. Empty input still returns a
 * well-formed all-zero totals row at `fallbackCurrency` so the footer renders sensibly even with no
 * data (rather than the caller special-casing an empty table).
 */
export function totalMarketplaceRow(
  rows: MarketplaceRow[],
  fallbackCurrency: string,
): MarketplaceTotals {
  const totals = emptyTotals(rows[0]?.currency ?? fallbackCurrency)
  for (const row of rows) {
    totals.grossSalesMinor += row.grossSalesMinor
    totals.transactionCount += row.transactionCount
    totals.feeMinor += row.feeMinor
    totals.netSettledMinor += row.netSettledMinor
    totals.settlementCount += row.settlementCount
    totals.outstandingMinor += row.outstandingMinor
  }
  return totals
}
