// Pure helpers for the Income Statement (Laba Rugi) account-detail drawer — no React, no fetch, no
// formatting, so the drill-down's sort + share maths are unit-testable in isolation (the house idiom,
// cf. registerFloat.ts). Amounts are integer minor units in the statement's single base currency
// (rule 8); the drawer formats them at the edge.
import type { IncomeLine } from './api'

/** One row of the P&L account-detail drawer: an account with its share of the section total. */
export interface IncomeDetailRow {
  accountCode: string
  name: string
  amountMinor: number
  /** Fraction of the section total: netMinor / totalMinor (0 when the total is 0; negative for a
   * contra line that nets the total down). The drawer clamps this to [0,1] for the bar width. */
  share: number
}

/** Chart-of-accounts code→name lookup; falls back to the raw code when the account isn't in the map. */
export function accountName(names: ReadonlyMap<string, string>, code: string): string {
  return names.get(code) ?? code
}

/**
 * The account rows for one P&L section (revenue or expense), LARGEST FIRST. Each row's `share` is its
 * fraction of `totalMinor` (0 when the total is 0). Order is by signed amount descending, so contra
 * lines (negative) sink to the bottom. Pure — the drawer supplies formatting + the code→name map.
 */
export function detailRows(
  lines: readonly IncomeLine[],
  totalMinor: number,
  names: ReadonlyMap<string, string>,
): IncomeDetailRow[] {
  return lines
    .map((l) => ({
      accountCode: l.accountCode,
      name: accountName(names, l.accountCode),
      amountMinor: l.netMinor,
      share: totalMinor !== 0 ? l.netMinor / totalMinor : 0,
    }))
    .sort((a, b) => b.amountMinor - a.amountMinor)
}
