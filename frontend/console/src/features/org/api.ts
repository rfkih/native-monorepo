import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

// ---------------------------------------------------------------------------
// Outlet shape returned by the cashier-accessible endpoint
// ---------------------------------------------------------------------------

export interface OutletSummary {
  id: string
  name: string
}

/**
 * GET /api/v1/outlets — active outlets for the current company, ordered by name.
 * Returns an empty array when the tenant has no outlets or the request fails,
 * so the picker silently hides rather than breaking the POS.
 *
 * Pass null for companyId to disable the query (no company session yet).
 */
export function useOutlets(companyId: string | null, actor = '') {
  return useQuery<OutletSummary[]>({
    enabled: !!companyId,
    queryKey: ['outlets', companyId],
    retry: false,
    queryFn: async () => {
      const result = await apiFetch<OutletSummary[]>('/api/v1/outlets', {
        tenant: { companyId: companyId!, actor },
      })
      return result ?? []
    },
  })
}

/** Org unit types as returned by the backend (ADR 0012 — flat tree, no branch level). */
export type OrgUnitType = 'BUSINESS_UNIT' | 'OUTLET' | 'TEAM'

/** Mirror of org-service OrgUnitListResponse. */
export interface OrgUnit {
  id: string
  name: string
  type: OrgUnitType
  parentId: string | null
  active: boolean
}

/** Mirror of org-service OrgUnitResponse (write-path response). */
export interface OrgUnitResponse {
  id: string
  name: string
  type: OrgUnitType
  parentId: string | null
  active: boolean
}

/** POST /api/v1/org-units body (mirrors CreateOrgUnitRequest). */
export interface CreateOrgUnitBody {
  name: string
  /** Must match a valid OrgUnitType string; validated by the domain. */
  type: string
  parentId: string | null
}

/**
 * PATCH /api/v1/org-units/{id} body (mirrors PatchOrgUnitRequest).
 * All fields are optional; only set the ones needed for the specific operation.
 * Use `reparent: true` + `parentId` to move; omit `reparent` to leave the parent unchanged.
 */
export interface PatchOrgUnitBody {
  name?: string
  reparent?: boolean
  parentId?: string | null
  deactivate?: boolean
  reactivate?: boolean
}

/** GET /api/v1/org-units — flat list; tree is built client-side from parentId. */
export function useOrgUnits(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    placeholderData: keepPreviousData,
    queryKey: ['orgUnits', companyId],
    queryFn: () =>
      apiFetch<OrgUnit[]>('/api/v1/org-units', {
        tenant: { companyId, actor },
      }),
  })
}

/** POST /api/v1/org-units — create a child (or top-level) org unit. */
export function useCreateOrgUnit(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateOrgUnitBody) =>
      apiFetch<OrgUnitResponse>('/api/v1/org-units', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['orgUnits', companyId] })
    },
  })
}

/** PATCH /api/v1/org-units/{id} — rename, move, deactivate, or reactivate. */
export function usePatchOrgUnit(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: PatchOrgUnitBody }) =>
      apiFetch<OrgUnitResponse>(`/api/v1/org-units/${id}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['orgUnits', companyId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Org-unit hub (detail page) data
// ---------------------------------------------------------------------------

/** One child outlet's slice of a unit P&L rollup (mirror of finance OutletPnlRow). */
export interface UnitPnlOutletRow {
  orgUnitId: string
  /** Display name from finance's cached org tree — may lag hydration. */
  name: string | null
  active: boolean
  revenueMinor: number
  expenseMinor: number
  netMinor: number
}

/** Mirror of finance UnitPnlResponse (GET /api/v1/pnl/org-units/{id}). */
export interface UnitPnlResponse {
  orgUnitId: string
  period: string
  revenueMinor: number
  expenseMinor: number
  netMinor: number
  currency: string
  usesIllustrativeRules: boolean
  /** Per-child-outlet breakdown, revenue-descending; empty for an outlet-type unit. */
  outlets: UnitPnlOutletRow[]
}

/**
 * GET /api/v1/pnl/org-units/{unitId}?period=&currency= — the per-unit P&L rollup. Sends the
 * company base currency as the hint so an empty period renders as zeros (200) instead of 204;
 * null only when finance does not know the unit yet (org_unit_ref hydration lag — render zeros,
 * never an error).
 */
export function useUnitPnl(params: {
  companyId: string
  actor: string
  unitId: string
  period: string
  baseCurrency: string
  enabled: boolean
}) {
  const { companyId, actor, unitId, period, baseCurrency, enabled } = params
  return useQuery({
    enabled,
    placeholderData: keepPreviousData,
    queryKey: ['unitPnl', companyId, unitId, period],
    queryFn: () =>
      apiFetch<UnitPnlResponse>(
        `/api/v1/pnl/org-units/${unitId}?period=${period}&currency=${encodeURIComponent(baseCurrency)}`,
        { tenant: { companyId, actor } },
      ),
  })
}

/** Mirror of org-service OrgUnitUserResponse (GET /api/v1/org-units/{id}/users). */
export interface OrgUnitUser {
  userId: string
  orgUnitId: string
  outletName: string
}

/**
 * GET /api/v1/org-units/{unitId}/users — active user↔outlet assignments under a unit (the
 * People tab). Identity fields are joined client-side from the cached team list.
 */
export function useUnitUsers(params: {
  companyId: string
  actor: string
  unitId: string
  enabled: boolean
}) {
  const { companyId, actor, unitId, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['unitUsers', companyId, unitId],
    queryFn: async () => {
      const result = await apiFetch<OrgUnitUser[]>(`/api/v1/org-units/${unitId}/users`, {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}
