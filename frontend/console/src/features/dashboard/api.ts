import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

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
