import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

// ---------------------------------------------------------------------------
// Outlet shape returned by the cashier-accessible endpoint
// ---------------------------------------------------------------------------

/** The whitelisted business verticals (server-authoritative; UI copy of the list). */
export type Vertical = 'restaurant' | 'carwash' | 'barbershop'

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

/**
 * Org unit types as returned by the backend. Since ADR 0070 there is exactly ONE: the tree is flat
 * (`company > outlet`), the division (business-unit) and team levels are gone, and every org unit
 * is a top-level outlet. Kept as a named type because the wire still carries the field.
 */
export type OrgUnitType = 'OUTLET'

/** Mirror of org-service OrgUnitListResponse. */
export interface OrgUnit {
  id: string
  name: string
  type: OrgUnitType
  /** Always null since ADR 0070 — the vertical lives on the COMPANY now. */
  vertical: string | null
  /** Always null since ADR 0070 — an outlet has no parent. */
  parentId: string | null
  active: boolean
}

/** Mirror of org-service OrgUnitResponse (write-path response). */
export interface OrgUnitResponse {
  id: string
  name: string
  type: OrgUnitType
  /** Always null since ADR 0070 (see {@link OrgUnit}). */
  vertical: string | null
  /** Always null since ADR 0070 (see {@link OrgUnit}). */
  parentId: string | null
  active: boolean
}

/**
 * POST /api/v1/org-units body (mirrors CreateOrgUnitRequest). ADR 0070: an outlet is the only kind
 * and has no parent, so `name` is the whole payload — the server defaults the type and rejects a
 * supplied `parentId` with a 400.
 */
export interface CreateOrgUnitBody {
  name: string
}

/**
 * PATCH /api/v1/org-units/{id} body (mirrors PatchOrgUnitRequest).
 * All fields are optional; only set the ones needed for the specific operation.
 * ADR 0070 removed the move/reparent operation — a flat tree has nowhere to move an outlet to.
 */
export interface PatchOrgUnitBody {
  name?: string
  deactivate?: boolean
  reactivate?: boolean
}

// ADR 0049 P3b — `/api/v1/org-units/**` is DASHBOARD_ROLES-gated (see RoutingConfig.java), so every
// hook below uses the PERSONAL bearer (`auth: 'personal'` — the elevation token on a device
// terminal; identical to the single login token for a normal `user` login). {@link useOutlets}
// above is DELIBERATELY untouched — `/api/v1/outlets` is POS_ROLES (readable by a bare cashier), so
// it must keep using the default outlet bearer (OutletGate resolves the terminal's outlet on every
// POS boot, elevated or not).

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
        auth: 'personal',
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
        auth: 'personal',
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['orgUnits', companyId] })
    },
  })
}

/**
 * DELETE /api/v1/org-units/{id} — permanently remove an EMPTY outlet. The server rejects an outlet
 * that still has an assigned login with 409
 * (`org-unit-has-data`) — deactivate it instead. This is the "remove a mistake" path; a unit with
 * real data must be deactivated (preserving history).
 */
export function useDeleteOrgUnit(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<null>(`/api/v1/org-units/${id}`, {
        method: 'DELETE',
        tenant: { companyId, actor },
        auth: 'personal',
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
        auth: 'personal',
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
        { tenant: { companyId, actor }, auth: 'personal' },
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
        auth: 'personal',
      })
      return result ?? []
    },
  })
}
