import { describe, expect, it } from 'vitest'
import { DISPOSAL_PROCEEDS, balanceSheetCsv, cashFlowCsv, incomeCsv, type CsvRow } from '../statementsCsv'
import type { BalanceSheetResponse, CashFlowResponse, IncomeStatementResponse } from '../api'

// A translate that echoes the key: the test cares about SHAPE, not copy. Account names come through
// the real accountLabels map, so a known code resolves to its key and an unknown one to undefined.
const ctx = { translate: (k: string) => k, companyName: 'Warung Kemang' }

const income: IncomeStatementResponse = {
  period: '2026-09',
  currency: 'IDR',
  revenueLines: [
    { accountCode: '4000', accountType: 'REVENUE', netMinor: 148_500_000, currency: 'IDR' },
    { accountCode: '4010', accountType: 'REVENUE', netMinor: -4_200_000, currency: 'IDR' },
  ],
  expenseLines: [{ accountCode: '5100', accountType: 'EXPENSE', netMinor: 52_400_000, currency: 'IDR' }],
  totalRevenueMinor: 144_300_000,
  totalExpenseMinor: 52_400_000,
  netMinor: 91_900_000,
  usesIllustrativeRules: false,
}

const balance: BalanceSheetResponse = {
  asOf: '2026-09',
  currency: 'IDR',
  assetLines: [
    { accountCode: '1900', accountType: 'ASSET', balanceMinor: 18_400_000, currency: 'IDR' },
    { accountCode: '1300', accountType: 'ASSET', balanceMinor: 0, currency: 'IDR' },
  ],
  liabilityLines: [{ accountCode: '2000', accountType: 'LIABILITY', balanceMinor: 22_640_000, currency: 'IDR' }],
  equityLines: [
    { accountCode: '3000', accountType: 'EQUITY', balanceMinor: 95_000_000, currency: 'IDR' },
    { accountCode: '3000-RETAINED-EARNINGS', accountType: 'EQUITY', balanceMinor: 1_000, currency: 'IDR' },
  ],
  totalAssetsMinor: 18_400_000,
  totalLiabilitiesMinor: 22_640_000,
  totalEquityMinor: 95_001_000,
  totalLiabilitiesAndEquityMinor: 117_641_000,
  retainedEarningsMinor: 1_000,
  usesIllustrativeRules: false,
}

const cash: CashFlowResponse = {
  period: '2026-09',
  currency: 'IDR',
  netIncomeMinor: 27_188_000,
  operatingLines: [{ accountCode: '1590', accountType: 'ASSET', amountMinor: 1_041_000 }],
  cashFromOperatingMinor: 28_229_000,
  investingLines: [
    { accountCode: '1500', accountType: 'ASSET', amountMinor: -6_400_000 },
    { accountCode: DISPOSAL_PROCEEDS, accountType: 'ASSET', amountMinor: 1_200_000 },
  ],
  cashFromInvestingMinor: -5_200_000,
  financingLines: [{ accountCode: '3000', accountType: 'EQUITY', amountMinor: -12_000_000 }],
  cashFromFinancingMinor: -12_000_000,
  netChangeInCashMinor: 11_029_000,
  cashMovementMinor: 11_029_000,
  reconciled: true,
  usesIllustrativeRules: false,
}

/** Every row that carries a figure: three cells, the figure in column C (index 2), as a number. */
function figureRows(rows: CsvRow[]): CsvRow[] {
  return rows.filter((r) => r.length === 3 && typeof r[2] === 'number')
}

describe('the column contract — code | name | amount, totals with an empty code cell', () => {
  it.each([
    ['income', () => incomeCsv(ctx, income)],
    ['balance sheet', () => balanceSheetCsv(ctx, balance)],
    ['cash flow', () => cashFlowCsv(ctx, cash)],
  ])('%s: every figure sits in column C, so SUM(C:C) reaches the totals too', (_name, build) => {
    const { rows } = build()
    const figures = figureRows(rows)
    expect(figures.length).toBeGreaterThan(3)
    for (const r of figures) {
      expect(typeof r[0]).toBe('string')
      expect(typeof r[1]).toBe('string')
      expect(typeof r[2]).toBe('number')
    }
    // No row anywhere carries its amount in column B — the bug this file exists to prevent.
    for (const r of rows) expect(typeof r[1]).not.toBe('number')
  })

  it('a total row leaves the code cell empty and names itself in the NAME cell', () => {
    const { rows } = incomeCsv(ctx, income)
    const totalRevenue = rows.find((r) => r[1] === 'statements.totalRevenue')
    expect(totalRevenue).toEqual(['', 'statements.totalRevenue', 144_300_000])
  })
})

describe('incomeCsv', () => {
  it('names each account beside its code and flips the net label by sign', () => {
    const { filename, rows } = incomeCsv(ctx, income)
    expect(filename).toBe('income-statement-2026-09.csv')
    expect(rows[0]).toEqual(['Warung Kemang', 'statements.scopeAllUnits'])
    expect(rows).toContainEqual(['4000', 'statements.accounts.salesRevenue', 148_500_000])
    expect(rows.at(-1)).toEqual(['', 'statements.netProfit', 91_900_000])

    const loss = incomeCsv(ctx, { ...income, netMinor: -1 })
    expect(loss.rows.at(-1)).toEqual(['', 'statements.netLoss', -1])
  })

  it('a contra line keeps its negative sign — the sheet must net the same way the statement does', () => {
    const { rows } = incomeCsv(ctx, income)
    expect(rows).toContainEqual(['4010', 'statements.accounts.salesDiscount', -4_200_000])
  })
})

describe('balanceSheetCsv', () => {
  it('keeps the FORMAL section words and every line, zeros included — the accountant’s artefact', () => {
    const { filename, rows } = balanceSheetCsv(ctx, balance)
    expect(filename).toBe('balance-sheet-2026-09.csv')
    expect(rows).toContainEqual(['statements.assets'])
    expect(rows).toContainEqual(['statements.liabilities'])
    expect(rows).toContainEqual(['statements.equity'])
    // The zero row the page hides is still in the file.
    expect(rows).toContainEqual(['1300', 'statements.accounts.vatInput', 0])
  })

  it('the synthetic profit row exports under its code, named — nothing is dropped', () => {
    const { rows } = balanceSheetCsv(ctx, balance)
    const synthetic = rows.find((r) => r[0] === '3000-RETAINED-EARNINGS')
    expect(synthetic?.[2]).toBe(1_000)
    expect(synthetic?.[1]).toBe('statements.accounts.accumulatedProfit')
  })
})

describe('cashFlowCsv', () => {
  it('net income leads operating as a code-less row; disposal proceeds export by label alone', () => {
    const { filename, rows } = cashFlowCsv(ctx, cash)
    expect(filename).toBe('cash-flow-2026-09.csv')
    const operatingHeading = rows.findIndex((r) => r[0] === 'statements.cashFlow.operating')
    expect(rows[operatingHeading + 1]).toEqual(['', 'statements.cashFlow.netIncome', 27_188_000])
    expect(rows).toContainEqual(['', 'statements.cashFlow.disposalProceeds', 1_200_000])
    expect(rows.find((r) => r[0] === DISPOSAL_PROCEEDS)).toBeUndefined()
    expect(rows.at(-1)).toEqual(['', 'statements.cashFlow.netChange', 11_029_000])
  })
})
