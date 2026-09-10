/**
 * Pure shaping helpers for the Balance Sheet (Neraca) — no React, no fetch, no formatting, so the
 * grouping, zero-row and unnatural-balance rules are unit-testable on their own (the house idiom,
 * cf. `incomeDetail.ts` / `openingBalances/lines.ts`). Amounts stay integer minor units (rule 8);
 * the page formats them at the edge.
 *
 * The three rules this module owns, and why each exists:
 *
 *  1. ORDER. The statement endpoint returns lines `ORDER BY account_code`, which buries Kas (1900)
 *     below prepaid tax and accumulated depreciation — the one figure an owner opens the page for
 *     sorts tenth. Assets are re-ordered here by how quickly each turns into money.
 *  2. ZERO ROWS. The GL query keeps any account that ever moved
 *     (`HAVING SUM(debit) <> 0 OR SUM(credit) <> 0`), so a settled clearing account still renders as
 *     a row worth nothing. A balance sheet omits zero balances; the page offers a reveal so nothing
 *     disappears silently.
 *  3. UNNATURAL BALANCES. A negative asset is physically impossible — you cannot own less than none
 *     of something — and in this product it is the visible symptom of stock being expensed twice.
 *     It must not render in the same ink as every other figure.
 */
import type { BalanceLine } from './api'
import { ACCOUNT_LABEL_KEYS } from './accountLabels'

/**
 * The asset accounts where a negative balance is genuinely IMPOSSIBLE rather than merely unusual —
 * things the business physically holds. An ALLOWLIST, not a blocklist: most asset accounts can
 * legitimately swing negative from ordinary timing, and a red "go investigate this" banner over a
 * healthy, self-correcting balance costs more trust than it saves.
 *
 * Deliberately excluded, with the reason each one goes negative on its own:
 *  - 1590 accumulated depreciation — a contra-asset, negative BY DESIGN.
 *  - 1901 / 1902 QRIS + card settlement clearing — a settlement landing before its capture leaves
 *    the clearing account credit-balanced until reconciliation catches up.
 *  - 1200 / 1250 receivables — a customer overpayment, or a marketplace settlement received ahead
 *    of the sale posting.
 *  - 1000 bank — an account can be overdrawn.
 *  - 1300 / 1310 / 1400 tax and prepayments — net positions that swing either way by period.
 *
 * That leaves stock and equipment, which is exactly the case `unnatural.body` describes ("a
 * purchase recorded as an expense twice") — see [[inventory-expense-double-count]].
 */
export const IMPOSSIBLY_NEGATIVE_ASSET_CODES: ReadonlySet<string> = new Set(['1100', '1500'])

/**
 * The asset groups, in display order, each listing its member codes in DISPLAY order — so Kas leads
 * the statement and the rest follow by how near they are to being spendable money. Any asset code
 * not named here falls into the catch-all group, so a newly seeded account is never dropped from
 * the page (`balanceSheetView.test.ts` asserts the partition stays total).
 */
export const ASSET_GROUPS: readonly { labelKey: string; codes: readonly string[] }[] = [
  {
    labelKey: 'statements.groups.liquid',
    codes: ['1900', '1000', '1901', '1902', '1200', '1250'],
  },
  { labelKey: 'statements.groups.goods', codes: ['1100', '1500', '1590'] },
  { labelKey: 'statements.groups.prepaid', codes: ['1300', '1310', '1400'] },
]

/** The catch-all group's label — holds any asset account the groups above don't name. */
export const OTHER_ASSETS_LABEL_KEY = 'statements.groups.other'

/** Equipment at cost, and the depreciation booked against it. */
export const FIXED_ASSET_COST_CODE = '1500'
export const ACCUMULATED_DEPRECIATION_CODE = '1590'

/**
 * Shows equipment at what it is WORTH — cost less the depreciation booked against it — as a single
 * row. Splitting an asset across a cost line and a negative contra line is an accountant's
 * convention; an owner asking what their equipment is worth means the net figure, and reading it
 * off two rows is arithmetic the page can do for them.
 *
 * Nothing is lost: the CSV export carries every line straight from the server, cost and
 * accumulated depreciation included, and the group subtotal is unchanged either way (the netted row
 * holds exactly what the two rows summed to).
 *
 * Only nets when BOTH lines are present — depreciation booked with no cost line stays visible
 * rather than being silently folded away.
 */
export function netFixedAssetLines(lines: readonly BalanceLine[]): BalanceLine[] {
  const cost = lines.find((l) => l.accountCode === FIXED_ASSET_COST_CODE)
  const depreciation = lines.find((l) => l.accountCode === ACCUMULATED_DEPRECIATION_CODE)
  if (!cost || !depreciation) return [...lines]

  // 1590 is credit-normal inside a debit-normal group, so its balance is already negative here.
  const netBookValue = cost.balanceMinor + depreciation.balanceMinor
  return lines
    .filter((l) => l.accountCode !== ACCUMULATED_DEPRECIATION_CODE)
    .map((l) => (l.accountCode === FIXED_ASSET_COST_CODE ? { ...l, balanceMinor: netBookValue } : l))
}

/**
 * A netted equipment row is worth showing even at zero. Fully depreciated does not mean gone: the
 * business still owns the equipment, and hiding the row as a zero balance would tell an owner they
 * have no equipment at all.
 */
export function isNettedFixedAssetRow(line: BalanceLine): boolean {
  return line.accountCode === FIXED_ASSET_COST_CODE
}

/** One asset group ready to render: its heading key, its lines in display order, and its subtotal. */
export interface AssetGroup {
  labelKey: string
  lines: BalanceLine[]
  subtotalMinor: number
}

/**
 * Splits lines into the ones worth showing and the ones that net to nothing. Hiding a zero row
 * never moves a total — zero adds zero — so the section totals the server sent stay authoritative.
 */
export function splitZeroLines(lines: readonly BalanceLine[]): {
  visible: BalanceLine[]
  hidden: BalanceLine[]
} {
  const visible: BalanceLine[] = []
  const hidden: BalanceLine[] = []
  for (const line of lines) (line.balanceMinor === 0 ? hidden : visible).push(line)
  return { visible, hidden }
}

/**
 * Groups asset lines by how quickly each turns into money, dropping groups that end up empty (a
 * warung has no prepaid tax, and an empty heading is noise). Every input line lands in exactly one
 * group; each subtotal sums the lines actually in that group, so the subtotals always add up to the
 * server's total assets.
 */
export function groupAssetLines(lines: readonly BalanceLine[]): AssetGroup[] {
  // Tracked BY POSITION, not by account code: the endpoint groups by account_code today, but if it
  // ever returned two rows for one code, a code-keyed map would silently drop one of them and the
  // subtotals would stop reconciling to the server's total.
  const taken = new Array<boolean>(lines.length).fill(false)
  const groups: AssetGroup[] = []

  for (const group of ASSET_GROUPS) {
    const picked: BalanceLine[] = []
    for (const code of group.codes) {
      lines.forEach((line, index) => {
        if (!taken[index] && line.accountCode === code) {
          taken[index] = true
          picked.push(line)
        }
      })
    }
    if (picked.length > 0) {
      groups.push({ labelKey: group.labelKey, lines: picked, ...subtotal(picked) })
    }
  }

  // Anything the groups above don't name keeps the server's order rather than vanishing.
  const rest = lines.filter((_, index) => !taken[index])
  if (rest.length > 0) {
    groups.push({ labelKey: OTHER_ASSETS_LABEL_KEY, lines: rest, ...subtotal(rest) })
  }
  return groups
}

function subtotal(lines: readonly BalanceLine[]): { subtotalMinor: number } {
  return { subtotalMinor: lines.reduce((sum, l) => sum + l.balanceMinor, 0) }
}

/**
 * The asset lines carrying a balance that cannot be real: negative, on an account that holds
 * something physical. These get flagged on the row AND called out above the tables, because they
 * are the only thing on a balance sheet that asks the reader to go and do something — which is
 * exactly why the rule is an allowlist (see {@link IMPOSSIBLY_NEGATIVE_ASSET_CODES}) and not
 * "every negative asset".
 */
export function unnaturalAssetLines(lines: readonly BalanceLine[]): BalanceLine[] {
  return lines.filter(
    (l) => l.balanceMinor < 0 && IMPOSSIBLY_NEGATIVE_ASSET_CODES.has(l.accountCode),
  )
}

/** The synthetic profit row finance-service appends to every balance sheet (not a chart account). */
export const RETAINED_EARNINGS_ACCOUNT = '3000-RETAINED-EARNINGS'

/**
 * One row as the statement tables want it, still unformatted and un-translated: `labelKey` is set
 * only for the synthetic profit row (its code means nothing to a reader, so it shows a name and no
 * code chip); `flagged` marks an impossible balance; `printOnly` keeps a hidden zero row in the DOM
 * for the printout. The page turns `labelKey` into copy and formats the amount at the edge.
 */
export interface BalanceDisplayLine {
  accountCode: string
  labelKey?: string
  amountMinor: number
  flagged?: boolean
  printOnly?: boolean
}

export interface BalanceSheetDisplay {
  assetGroups: { labelKey: string; lines: BalanceDisplayLine[]; subtotalMinor: number }[]
  liabilityLines: BalanceDisplayLine[]
  equityLines: BalanceDisplayLine[]
  hiddenAssets: number
  hiddenLiabilities: number
  hiddenEquity: number
  /** The asset lines carrying a balance that cannot be real — see {@link unnaturalAssetLines}. */
  flagged: BalanceLine[]
}

/**
 * Everything the Neraca decides before it renders, in one pure step: equipment netted to book value,
 * assets grouped by liquidity, impossible balances flagged (assets only — a liability must never
 * inherit the treatment because its code collides), zero rows folded away unless `showZeros`, the
 * netted equipment row kept even at zero (fully depreciated is not gone), and the synthetic profit
 * row given a name key instead of a code. The desktop page and the phone screen both render THIS,
 * so they cannot disagree on a row.
 */
export function displayBalanceSheet(
  data: { assetLines: BalanceLine[]; liabilityLines: BalanceLine[]; equityLines: BalanceLine[] },
  opts: { showZeros: boolean },
): BalanceSheetDisplay {
  const assetLines = netFixedAssetLines(data.assetLines)
  const flagged = unnaturalAssetLines(assetLines)
  const flaggedCodes = new Set(flagged.map((l) => l.accountCode))

  const toDisplay =
    (flag: boolean) =>
    (l: BalanceLine): BalanceDisplayLine => ({
      accountCode: l.accountCode,
      amountMinor: l.balanceMinor,
      flagged: flag && flaggedCodes.has(l.accountCode),
      printOnly: !opts.showZeros && l.balanceMinor === 0 && !isNettedFixedAssetRow(l),
    })

  return {
    assetGroups: groupAssetLines(assetLines).map((g) => ({
      labelKey: g.labelKey,
      lines: g.lines.map(toDisplay(true)),
      subtotalMinor: g.subtotalMinor,
    })),
    liabilityLines: data.liabilityLines.map(toDisplay(false)),
    equityLines: data.equityLines.map((l) =>
      l.accountCode === RETAINED_EARNINGS_ACCOUNT
        ? { accountCode: '', labelKey: ACCOUNT_LABEL_KEYS[RETAINED_EARNINGS_ACCOUNT], amountMinor: l.balanceMinor }
        : toDisplay(false)(l),
    ),
    hiddenAssets: splitZeroLines(assetLines).hidden.length,
    hiddenLiabilities: splitZeroLines(data.liabilityLines).hidden.length,
    hiddenEquity: splitZeroLines(data.equityLines).hidden.length,
    flagged,
  }
}
