/**
 * The three statements' CSV exports as pure builders — one source for the desktop Export button and
 * the phone Export sheet, so the two can never drift (and the column contract below can be tested).
 *
 * COLUMN CONTRACT: `code | name | amount`. A total or subtotal row leaves the code cell EMPTY and puts
 * its label in the NAME cell, so every figure in the file lands in column C and `SUM(C:C)` reaches the
 * totals too. This once went wrong in all three statements at once — detail rows gained a name column
 * and the total rows did not, so amounts sat in C for lines and B for totals and the spreadsheet's
 * sum quietly skipped every total. `statementsCsv.test.ts` pins it.
 *
 * Every line carries the account NAME beside its code: the spreadsheet is read by the same people as
 * the page, and a bare code is just as opaque there. The export keeps the FORMAL wording (assets /
 * liabilities / equity) and every line including the ones worth nothing — the spreadsheet is the
 * accountant's artefact, the page is the owner's.
 *
 * No React, no i18n hook: the caller hands in `translate` (the same shape `accountLabel` takes) and
 * the entity line, and gets back the rows `downloadCsv` wants.
 */
import { accountLabel } from './accountLabels'
import type {
  BalanceLine,
  BalanceSheetResponse,
  CashFlowLine,
  CashFlowResponse,
  IncomeLine,
  IncomeStatementResponse,
} from './api'

/** The synthetic investing row finance-service emits for asset-sale proceeds (not a chart account). */
export const DISPOSAL_PROCEEDS = 'DISPOSAL_PROCEEDS'

export type CsvRow = (string | number)[]

export interface CsvContext {
  translate: (key: string) => string
  /** The reporting entity — the first line of every export. */
  companyName: string
}

export interface CsvFile {
  filename: string
  rows: CsvRow[]
}

const total = (label: string, amountMinor: number): CsvRow => ['', label, amountMinor]

export function incomeCsv(ctx: CsvContext, data: IncomeStatementResponse): CsvFile {
  const t = ctx.translate
  const line = (l: IncomeLine): CsvRow => [l.accountCode, accountLabel(t, l.accountCode) ?? '', l.netMinor]
  const profit = data.netMinor >= 0
  return {
    filename: `income-statement-${data.period}.csv`,
    rows: [
      [ctx.companyName, t('statements.scopeAllUnits')],
      [t('statements.incomeTitle'), data.period, data.currency],
      [],
      [t('statements.revenue')],
      ...data.revenueLines.map(line),
      total(t('statements.totalRevenue'), data.totalRevenueMinor),
      [],
      [t('statements.expense')],
      ...data.expenseLines.map(line),
      total(t('statements.totalExpense'), data.totalExpenseMinor),
      [],
      total(profit ? t('statements.netProfit') : t('statements.netLoss'), data.netMinor),
    ],
  }
}

export function balanceSheetCsv(ctx: CsvContext, data: BalanceSheetResponse): CsvFile {
  const t = ctx.translate
  const line = (l: BalanceLine): CsvRow => [
    l.accountCode,
    accountLabel(t, l.accountCode) ?? '',
    l.balanceMinor,
  ]
  return {
    filename: `balance-sheet-${data.asOf}.csv`,
    rows: [
      [ctx.companyName, t('statements.scopeAllUnits')],
      [t('statements.balanceTitle'), data.asOf, data.currency],
      [],
      [t('statements.assets')],
      ...data.assetLines.map(line),
      total(t('statements.totalAssets'), data.totalAssetsMinor),
      [],
      [t('statements.liabilities')],
      ...data.liabilityLines.map(line),
      total(t('statements.totalLiabilities'), data.totalLiabilitiesMinor),
      [],
      [t('statements.equity')],
      ...data.equityLines.map(line),
      total(t('statements.totalEquity'), data.totalEquityMinor),
    ],
  }
}

export function cashFlowCsv(ctx: CsvContext, data: CashFlowResponse): CsvFile {
  const t = ctx.translate
  // DISPOSAL_PROCEEDS is a synthetic marker, not a chart account: it exports under its localized
  // label alone, in the name cell, like every other row without a code.
  const line = (l: CashFlowLine): CsvRow =>
    l.accountCode === DISPOSAL_PROCEEDS
      ? ['', t('statements.cashFlow.disposalProceeds'), l.amountMinor]
      : [l.accountCode, accountLabel(t, l.accountCode) ?? '', l.amountMinor]
  return {
    filename: `cash-flow-${data.period}.csv`,
    rows: [
      [ctx.companyName, t('statements.scopeAllUnits')],
      [t('statements.cashFlow.title'), data.period, data.currency],
      [],
      [t('statements.cashFlow.operating')],
      total(t('statements.cashFlow.netIncome'), data.netIncomeMinor),
      ...data.operatingLines.map(line),
      total(t('statements.cashFlow.fromOperating'), data.cashFromOperatingMinor),
      [],
      [t('statements.cashFlow.investing')],
      ...data.investingLines.map(line),
      total(t('statements.cashFlow.fromInvesting'), data.cashFromInvestingMinor),
      [],
      [t('statements.cashFlow.financing')],
      ...data.financingLines.map(line),
      total(t('statements.cashFlow.fromFinancing'), data.cashFromFinancingMinor),
      [],
      total(t('statements.cashFlow.netChange'), data.netChangeInCashMinor),
    ],
  }
}
