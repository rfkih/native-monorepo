import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'

/**
 * ADR 0049 P3b — every call in this module targets a DASHBOARD_ROLES-gated back-office route
 * (`/api/v1/closes/**`), so it always uses the PERSONAL bearer (the elevation token on a device
 * terminal; identical to the single login token for a normal `user` login). Shadows the shared
 * `apiFetch` import so every call site below is correct with ZERO per-call changes.
 */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

/** Mirror of finance-service CloseHistoryResponse. */
export interface CloseHistoryItem {
  closeId: string
  period: string
  baseCurrency: string
  firstClose: boolean
  reconciled: boolean
  usesIllustrativeRules: boolean
}

/** Mirror of finance-service WithinCompanyCloseResponse. */
export interface CloseResponse {
  closeId: string
  period: string
  firstClose: boolean
  reconciled: boolean
  usesIllustrativeRules: boolean
  trialBalancePublishedGroups: string[]
}

/** POST /api/v1/closes body (mirrors WithinCompanyCloseRequest). baseCurrency is optional. */
export interface CloseBody {
  period: string
  baseCurrency?: string
}

/** GET /api/v1/closes — close history for the bound company, most-recent first. Always 200. */
export function useCloseHistory(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['closeHistory', companyId],
    queryFn: () =>
      apiFetch<CloseHistoryItem[]>('/api/v1/closes', {
        tenant: { companyId, actor },
      }),
  })
}

/** POST /api/v1/closes — close the bound company's current period. Idempotent. */
export function useClosePeriod(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CloseBody) =>
      apiFetch<CloseResponse>('/api/v1/closes', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['closeHistory', companyId] })
    },
  })
}
