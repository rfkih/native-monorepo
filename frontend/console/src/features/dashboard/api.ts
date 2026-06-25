import { useQueries, useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import { shiftPeriod } from '@/lib/period'

/** Mirror of finance-service PnlResponse (revenue/expense/net as minor + ISO-4217). */
export interface PnlResponse {
  period: string
  revenueMinor: number
  expenseMinor: number
  netMinor: number
  currency: string
  usesIllustrativeRules: boolean
  presentationCurrency?: string | null
  presentationRevenueMinor?: number | null
  presentationExpenseMinor?: number | null
  presentationNetMinor?: number | null
  usesStubFx?: boolean | null
  fxAsOf?: string | null
}

export function usePnl(params: {
  companyId: string
  actor: string
  period: string
  baseCurrency: string
  presentation?: string
  enabled: boolean
}) {
  const { companyId, actor, period, baseCurrency, presentation, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['pnl', companyId, period, presentation ?? 'native'],
    queryFn: () =>
      apiFetch<PnlResponse>('/api/v1/pnl', {
        tenant: { companyId, actor },
        // currency hint => an empty period returns a zero P&L (not 204); presentation is the lens.
        query: { period, currency: baseCurrency, presentation },
      }),
  })
}

/** One point in the trailing-window trend: the period it covers and its (nullable) P&L. */
export interface PnlTrendPoint {
  period: string
  data: PnlResponse | null
}

/**
 * The trailing `months`-month window of P&L ending at (and including) `period`, oldest first — the
 * series behind the dashboard sparkline and the revenue-vs-previous delta. Each month is an
 * independent query keyed identically to `usePnl`, so the current period is de-duplicated with the
 * hero query (no redundant request) and every point shares the same cache + presentation lens.
 */
export function usePnlTrend(params: {
  companyId: string
  actor: string
  period: string
  baseCurrency: string
  presentation?: string
  months: number
  enabled: boolean
}): PnlTrendPoint[] {
  const { companyId, actor, period, baseCurrency, presentation, months, enabled } = params
  const periods = Array.from({ length: months }, (_, i) => shiftPeriod(period, -(months - 1 - i)))
  const results = useQueries({
    queries: periods.map((p) => ({
      enabled,
      queryKey: ['pnl', companyId, p, presentation ?? 'native'],
      queryFn: () =>
        apiFetch<PnlResponse>('/api/v1/pnl', {
          tenant: { companyId, actor },
          query: { period: p, currency: baseCurrency, presentation },
        }),
    })),
  })
  return periods.map((p, i) => ({ period: p, data: results[i]?.data ?? null }))
}
