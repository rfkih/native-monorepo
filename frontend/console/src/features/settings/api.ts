import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { PlanTier } from '@/lib/featureTier'

interface PlanTierResponse {
  planTier: string
}

/**
 * PUT /api/v1/companies/current/plan-tier — the owner-only setter behind `/settings/features`
 * (P1 tier-mode). Tenant-scoped; the org-service edge independently re-checks the `owner` role, so
 * this call is UI convenience only, not the authorization boundary. ADR 0049 P3b: this route is
 * DASHBOARD_ROLES-gated (`/api/v1/companies/**`), so it uses the PERSONAL bearer — the elevation
 * token on a device terminal; identical to the single login token for a normal `user` login.
 *
 * On success, invalidates the `myCompanies` query — the exact read path `SessionProvider` hydrates
 * the session from — so the flip reaches the nav/dashboard for the toggling owner without a reload
 * (plan Risk 4). A prefix match (`['myCompanies']`) catches the query regardless of the `sub`/
 * `companyIds` suffix SessionProvider keys it with.
 */
export function useSetPlanTier(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (planTier: PlanTier) =>
      apiFetch<PlanTierResponse>('/api/v1/companies/current/plan-tier', {
        method: 'PUT',
        tenant: { companyId, actor },
        auth: 'personal',
        body: { planTier },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['myCompanies'] })
    },
  })
}
