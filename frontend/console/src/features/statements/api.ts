import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/** One Income Statement line — mirror of finance-service IncomeLineItem (net as minor + ISO-4217). */
export interface IncomeLine {
  accountCode: string
  accountType: string
  netMinor: number
  /** Always the statement's single base currency; the page formats with the top-level currency. */
  currency: string
}

/** Mirror of finance-service IncomeStatementResponse (all amounts integer minor units, rule 8). */
export interface IncomeStatementResponse {
  period: string
  currency: string
  revenueLines: IncomeLine[]
  expenseLines: IncomeLine[]
  totalRevenueMinor: number
  totalExpenseMinor: number
  netMinor: number
  usesIllustrativeRules: boolean
}

/** One Balance Sheet line — mirror of finance-service BalanceSheetLineItem (balance as minor). */
export interface BalanceLine {
  accountCode: string
  accountType: string
  balanceMinor: number
  currency: string
}

/** Mirror of finance-service BalanceSheetResponse (all amounts integer minor units, rule 8). */
export interface BalanceSheetResponse {
  asOf: string
  currency: string
  assetLines: BalanceLine[]
  liabilityLines: BalanceLine[]
  equityLines: BalanceLine[]
  totalAssetsMinor: number
  totalLiabilitiesMinor: number
  totalEquityMinor: number
  totalLiabilitiesAndEquityMinor: number
  retainedEarningsMinor: number
  usesIllustrativeRules: boolean
}

/**
 * GET /api/v1/statements/income?period=YYYY-MM. Unlike the P&L endpoint there is no currency hint:
 * a period with no GL entries returns 204 (apiFetch → null), which the page renders as an empty
 * state rather than a zeroed statement with a guessed currency.
 */
export function useIncomeStatement(params: {
  companyId: string
  actor: string
  period: string
  enabled: boolean
}) {
  const { companyId, actor, period, enabled } = params
  return useQuery({
    enabled,
    // Keep the prior period's figures on screen while stepping to a new month, so a period that
    // returns 204 (no entries) doesn't flash the previous figures then snap to the empty state.
    placeholderData: keepPreviousData,
    queryKey: ['incomeStatement', companyId, period],
    queryFn: () =>
      apiFetch<IncomeStatementResponse>('/api/v1/statements/income', {
        tenant: { companyId, actor },
        query: { period },
      }),
  })
}

/** One Cash Flow line — mirror of finance-service CashFlowLineItem (adjustment as minor units). */
export interface CashFlowLine {
  accountCode: string
  accountType: string
  amountMinor: number
}

/** Mirror of finance-service CashFlowResponse (indirect method; all amounts integer minor units). */
export interface CashFlowResponse {
  period: string
  currency: string
  netIncomeMinor: number
  operatingLines: CashFlowLine[]
  cashFromOperatingMinor: number
  investingLines: CashFlowLine[]
  cashFromInvestingMinor: number
  financingLines: CashFlowLine[]
  cashFromFinancingMinor: number
  netChangeInCashMinor: number
  cashMovementMinor: number
  reconciled: boolean
  usesIllustrativeRules: boolean
}

/** GET /api/v1/statements/cash-flow?period=YYYY-MM. 204 → null (no GL entries yet). */
export function useCashFlow(params: {
  companyId: string
  actor: string
  period: string
  enabled: boolean
}) {
  const { companyId, actor, period, enabled } = params
  return useQuery({
    enabled,
    placeholderData: keepPreviousData,
    queryKey: ['cashFlow', companyId, period],
    queryFn: () =>
      apiFetch<CashFlowResponse>('/api/v1/statements/cash-flow', {
        tenant: { companyId, actor },
        query: { period },
      }),
  })
}

/** GET /api/v1/statements/balance-sheet?asOf=YYYY-MM. 204 → null (no GL entries yet). */
export function useBalanceSheet(params: {
  companyId: string
  actor: string
  asOf: string
  enabled: boolean
}) {
  const { companyId, actor, asOf, enabled } = params
  return useQuery({
    enabled,
    // Keep the prior period's figures on screen while stepping months (see useIncomeStatement).
    placeholderData: keepPreviousData,
    queryKey: ['balanceSheet', companyId, asOf],
    queryFn: () =>
      apiFetch<BalanceSheetResponse>('/api/v1/statements/balance-sheet', {
        tenant: { companyId, actor },
        query: { asOf },
      }),
  })
}
