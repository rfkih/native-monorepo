import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'

/**
 * ADR 0049 P3b — every call in this module targets a DASHBOARD_ROLES-gated back-office route
 * (`/api/v1/consolidation-groups/**`, `/api/v1/groups/**`), so it always uses the PERSONAL bearer
 * (the elevation token on a device terminal; identical to the single login token for a normal
 * `user` login). Shadows the shared `apiFetch` import so every call site below is correct with ZERO
 * per-call changes.
 */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

/** Mirror of org-service ConsolidationGroupListResponse. */
export interface ConsolidationGroup {
  id: string
  name: string
  leadCompanyId: string
  reportingCurrency: string
}

/** Mirror of org-service GroupMembershipListResponse. */
export interface GroupMember {
  memberCompanyId: string
  effectiveFrom: string
  effectiveTo: string
  active: boolean
}

/**
 * ConsolidationState enum values from finance-service.
 * CLOSED is the only finalised state; the rest are gated hold-back states.
 */
export type ConsolidationState =
  | 'CLOSED'
  | 'PENDING_MEMBERS'
  | 'PENDING_MEMBERS_WARMING_UP'
  | 'MULTI_CURRENCY_UNSUPPORTED'
  | 'MEMBER_TRIAL_BALANCE_UNBALANCED'
  | 'MEMBER_TRIAL_BALANCE_MULTICURRENCY'
  | 'INTERCOMPANY_UNRECONCILED'
  | 'SUPERSEDED'

/** Mirror of finance-service GroupEliminationStatusResponse. */
export interface GroupEliminationStatus {
  totalReferences: number
  unreconciledReferences: number
  allReconciled: boolean
}

/**
 * Mirror of finance-service GroupConsolidationResponse.
 * All money fields are integer minor units in reportingCurrency.
 * 204 → null (no close for this period).
 */
export interface GroupConsolidationResponse {
  groupId: string
  period: string
  reportingCurrency: string
  groupRevenueMinor: number
  groupExpenseMinor: number
  groupNetMinor: number
  state: ConsolidationState
  usesStubFx: boolean
  usesIllustrativeRules: boolean
  usesSimplifiedTranslationPolicy: boolean
  closeRunSeq: number
  eliminations: GroupEliminationStatus
}

/** Mirror of finance-service GroupCloseResponse. */
export interface GroupCloseResponse {
  groupId: string
  period: string
  state: ConsolidationState
  closeRunSeq: number
  closed: boolean
  firstRun: boolean
}

/** Mirror of org-service GroupResponse (write-path response). */
export interface GroupResponse {
  id: string
  name: string
  leadCompanyId: string
  reportingCurrency: string
}

/** Mirror of org-service MembershipResponse (write-path response). */
export interface MembershipResponse {
  memberCompanyId: string
  effectiveFrom: string
  effectiveTo: string
  active: boolean
}

/** POST /api/v1/consolidation-groups body (mirrors CreateGroupRequest). */
export interface CreateGroupBody {
  name: string
  reportingCurrency: string
}

/** POST /api/v1/consolidation-groups/{groupId}/members body (mirrors AddMemberRequest). */
export interface AddMemberBody {
  memberCompanyId: string
}

/** GET /api/v1/consolidation-groups — lists all groups the bound company leads. */
export function useConsolidationGroups(params: {
  companyId: string
  actor: string
  enabled: boolean
}) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    placeholderData: keepPreviousData,
    queryKey: ['consolidationGroups', companyId],
    queryFn: () =>
      apiFetch<ConsolidationGroup[]>('/api/v1/consolidation-groups', {
        tenant: { companyId, actor },
      }),
  })
}

/** GET /api/v1/consolidation-groups/{groupId}/members — 404 if not the lead or unknown group. */
export function useGroupMembers(params: {
  companyId: string
  actor: string
  groupId: string
  enabled: boolean
}) {
  const { companyId, actor, groupId, enabled } = params
  return useQuery({
    enabled,
    placeholderData: keepPreviousData,
    queryKey: ['groupMembers', companyId, groupId],
    queryFn: () =>
      apiFetch<GroupMember[]>(`/api/v1/consolidation-groups/${groupId}/members`, {
        tenant: { companyId, actor },
      }),
  })
}

/**
 * GET /api/v1/groups/{groupId}/consolidation?period=YYYY-MM.
 * Returns 204 (null) when no close exists for the period yet.
 */
export function useGroupConsolidation(params: {
  companyId: string
  actor: string
  groupId: string
  period: string
  enabled: boolean
}) {
  const { companyId, actor, groupId, period, enabled } = params
  return useQuery({
    enabled,
    placeholderData: keepPreviousData,
    queryKey: ['groupConsolidation', companyId, groupId, period],
    queryFn: () =>
      apiFetch<GroupConsolidationResponse>(`/api/v1/groups/${groupId}/consolidation`, {
        tenant: { companyId, actor },
        query: { period },
      }),
  })
}

/** POST /api/v1/consolidation-groups — define a new group. */
export function useCreateGroup(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateGroupBody) =>
      apiFetch<GroupResponse>('/api/v1/consolidation-groups', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['consolidationGroups', companyId] })
    },
  })
}

/** POST /api/v1/consolidation-groups/{groupId}/members — add a member to a group. */
export function useAddGroupMember(params: { companyId: string; actor: string; groupId: string }) {
  const { companyId, actor, groupId } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AddMemberBody) =>
      apiFetch<MembershipResponse>(`/api/v1/consolidation-groups/${groupId}/members`, {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['groupMembers', companyId, groupId] })
    },
  })
}

/**
 * DELETE /api/v1/consolidation-groups/{groupId}/members/{memberCompanyId}.
 * 204 on idempotent re-remove (no active membership to close).
 */
export function useRemoveGroupMember(params: {
  companyId: string
  actor: string
  groupId: string
}) {
  const { companyId, actor, groupId } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (memberCompanyId: string) =>
      apiFetch<MembershipResponse | null>(
        `/api/v1/consolidation-groups/${groupId}/members/${memberCompanyId}`,
        {
          method: 'DELETE',
          tenant: { companyId, actor },
        },
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['groupMembers', companyId, groupId] })
    },
  })
}

/**
 * POST /api/v1/groups/{groupId}/consolidation/close?period=YYYY-MM&closeRunSeq=N.
 * Lead-admin only; triggers the group close pipeline.
 */
export function useRunGroupClose(params: { companyId: string; actor: string; groupId: string }) {
  const { companyId, actor, groupId } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ period, closeRunSeq }: { period: string; closeRunSeq: number }) =>
      apiFetch<GroupCloseResponse>(`/api/v1/groups/${groupId}/consolidation/close`, {
        method: 'POST',
        tenant: { companyId, actor },
        query: { period, closeRunSeq: String(closeRunSeq) },
      }),
    onSuccess: (_data, { period }) => {
      void queryClient.invalidateQueries({
        queryKey: ['groupConsolidation', companyId, groupId, period],
      })
    },
  })
}
