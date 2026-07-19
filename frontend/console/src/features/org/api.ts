import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/** Org unit types as returned by the backend. */
export type OrgUnitType = 'BUSINESS_UNIT' | 'BRANCH' | 'OUTLET' | 'TEAM'

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
