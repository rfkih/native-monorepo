import { keepPreviousData, useQueries, useQuery } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'
import { trailingPeriods } from './periodChartMath'

/**
 * ADR 0049 P3b — every call in this module targets a DASHBOARD_ROLES-gated back-office route
 * (`/api/v1/statements/**`), so it always uses the PERSONAL bearer (the elevation token on a device
 * terminal; identical to the single login token for a normal `user` login). Shadows the shared
 * `apiFetch` import so every call site below is correct with ZERO per-call changes.
 */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

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

// ---------------------------------------------------------------------------------------------
// Trends (Native Laporan — the phone chart that is also the period control)
// ---------------------------------------------------------------------------------------------

/**
 * Closed months do not change, and the chart asks for twelve of them at once. Five minutes keeps a
 * tab switch or a back-and-forth between months from re-issuing the whole window; the single-period
 * hooks above keep their own defaults, so the month on screen still refreshes as it always did.
 */
const TREND_STALE_MS = 5 * 60_000

/**
 * One point in a statement's trailing window: the period and its statement — null for a 204 (no
 * entries) AND for a request that failed, which is why `failed` exists: a month that could not be
 * loaded must never look like a month with nothing in it. `retry` re-issues that one month.
 */
export interface TrendPoint<T> {
  period: string
  data: T | null
  failed: boolean
  retry: () => void
}

/**
 * The trailing `months` balance sheets ending at (and including) `asOf`, oldest first — the net-worth
 * series behind the phone Neraca chart. There is no trend endpoint, so this is `months` independent
 * queries (the mockup's twelve-calls budget), each keyed IDENTICALLY to {@link useBalanceSheet} so
 * the month on screen shares its cache entry (and any in-flight request) with its column — the
 * single-period hook keeps its own `staleTime`, so a tapped month may still refresh on mount. A 204
 * month stays `null` and draws as a gap. Mirrors `dashboard/api.ts usePnlTrend`.
 */
export function useBalanceSheetTrend(params: {
  companyId: string
  actor: string
  asOf: string
  months: number
  enabled: boolean
}): TrendPoint<BalanceSheetResponse>[] {
  const { companyId, actor, asOf, months, enabled } = params
  const periods = trailingPeriods(asOf, months)
  const results = useQueries({
    queries: periods.map((p) => ({
      enabled,
      staleTime: TREND_STALE_MS,
      queryKey: ['balanceSheet', companyId, p],
      queryFn: () =>
        apiFetch<BalanceSheetResponse>('/api/v1/statements/balance-sheet', {
          tenant: { companyId, actor },
          query: { asOf: p },
        }),
    })),
  })
  return periods.map((p, i) => ({
    period: p,
    data: results[i]?.data ?? null,
    failed: results[i]?.isError ?? false,
    retry: () => void results[i]?.refetch(),
  }))
}

/** The trailing `months` cash-flow statements ending at `period` — see {@link useBalanceSheetTrend}. */
export function useCashFlowTrend(params: {
  companyId: string
  actor: string
  period: string
  months: number
  enabled: boolean
}): TrendPoint<CashFlowResponse>[] {
  const { companyId, actor, period, months, enabled } = params
  const periods = trailingPeriods(period, months)
  const results = useQueries({
    queries: periods.map((p) => ({
      enabled,
      staleTime: TREND_STALE_MS,
      queryKey: ['cashFlow', companyId, p],
      queryFn: () =>
        apiFetch<CashFlowResponse>('/api/v1/statements/cash-flow', {
          tenant: { companyId, actor },
          query: { period: p },
        }),
    })),
  })
  return periods.map((p, i) => ({
    period: p,
    data: results[i]?.data ?? null,
    failed: results[i]?.isError ?? false,
    retry: () => void results[i]?.refetch(),
  }))
}
