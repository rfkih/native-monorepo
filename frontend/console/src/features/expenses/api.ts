/**
 * Expense claims — the employee self-service client (ADR 0030, Phase E6) for
 * `/api/v1/me/expense-claims`. Mirrors features/ar/api.ts's hook + Idempotency-Key idiom and
 * lib/api.ts's `PageResponse` envelope handling (see employee-service's `ExpenseClaimReader`
 * Javadoc for the server side of the same envelope). Money on the wire is always integer minor
 * units + an ISO-4217 currency (rule 8) — see `lib/money` for Intl display formatting and
 * `./amount` for the major<->minor input conversion.
 */

import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, apiFetchBlob, apiUpload, type AuthTarget } from '@/lib/api'

interface TenantParams {
  companyId: string
  actor: string
}

// ---------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------

/** GET /api/v1/me/expense-claims/categories row (mirrors ExpenseCategoryResponse). */
export interface ExpenseCategory {
  id: string
  name: string
  glHint: string
  taxable: boolean
  active: boolean
}

/** GET /api/v1/me/expense-claims/categories — active categories a new claim may be raised against. */
export function useMyCategories(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['myExpenseCategories', companyId],
    queryFn: async () => {
      const result = await apiFetch<ExpenseCategory[]>('/api/v1/me/expense-claims/categories', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

// ---------------------------------------------------------------------------
// Categories — admin CRUD (manager surface, Phase E7; endpoints existed since E1)
// ---------------------------------------------------------------------------

/** The finance-service `gl_hint` whitelist an expense category may carry (mirrors employee-service
 *  `ExpenseCategory.GL_HINT_WHITELIST` — a new hint needs a finance mapping_rule seed first). */
export const GL_HINT_OPTIONS = ['', 'cogs', 'supplies', 'utilities'] as const
export type GlHint = (typeof GL_HINT_OPTIONS)[number]

/** GET /api/v1/expense-categories — the admin catalog list (ACTIVE only — see CategoriesAdmin). */
export function useCategories(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['expenseCategories', companyId],
    queryFn: async () => {
      const result = await apiFetch<ExpenseCategory[]>('/api/v1/expense-categories', {
        tenant: { companyId, actor },
        auth: 'personal',
      })
      return result ?? []
    },
  })
}

function invalidateCategories(queryClient: ReturnType<typeof useQueryClient>, companyId: string) {
  void queryClient.invalidateQueries({ queryKey: ['expenseCategories', companyId] })
  // A new/renamed/deactivated category also affects the employee-facing picker.
  void queryClient.invalidateQueries({ queryKey: ['myExpenseCategories', companyId] })
}

/** POST /api/v1/expense-categories body. */
export interface CreateCategoryBody {
  name: string
  glHint: string
  taxable: boolean
}

/** POST /api/v1/expense-categories — create a category. */
export function useCreateCategory(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCategoryBody) =>
      apiFetch<ExpenseCategory>('/api/v1/expense-categories', {
        method: 'POST',
        tenant: { companyId, actor },
        auth: 'personal',
        body,
      }),
    onSuccess: () => invalidateCategories(queryClient, companyId),
  })
}

/** PATCH /api/v1/expense-categories/{id} body — partial; a missing field leaves it unchanged. */
export interface UpdateCategoryBody {
  name?: string
  glHint?: string
  taxable?: boolean
  active?: boolean
}

/** PATCH /api/v1/expense-categories/{id} — partial update. */
export function useUpdateCategory(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateCategoryBody & { id: string }) =>
      apiFetch<ExpenseCategory>(`/api/v1/expense-categories/${id}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        auth: 'personal',
        body,
      }),
    onSuccess: () => invalidateCategories(queryClient, companyId),
  })
}

/** POST /api/v1/expense-categories/seed-defaults — idempotent; returns the full active list. */
export function useSeedDefaultCategories(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<ExpenseCategory[]>('/api/v1/expense-categories/seed-defaults', {
        method: 'POST',
        tenant: { companyId, actor },
        auth: 'personal',
      }),
    onSuccess: () => invalidateCategories(queryClient, companyId),
  })
}

// ---------------------------------------------------------------------------
// Claims — list + detail
// ---------------------------------------------------------------------------

export type ClaimStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REFUSED'
  | 'CANCELLED'
  | 'VOIDED'
  | 'REIMBURSED'

export type ReimbursementMethod = 'PAYROLL' | 'DIRECT'

/** One row of the caller's own claim list (mirrors MyExpenseClaimResponse). */
export interface MyExpenseClaim {
  id: string
  status: ClaimStatus
  amountMinor: number
  currency: string
  expenseDate: string
  merchant: string | null
  categoryName: string
  reimbursementMethod: ReimbursementMethod
  decidedBy: string | null
  decidedAt: string | null
  decisionComment: string | null
}

/** The ENGINEERING-STANDARDS §1.3 pagination envelope every collection GET returns (mirrors the
 *  backend's local `PageResponse<T>` record — no shared `libs/` version exists yet either side). */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

const EMPTY_PAGE: PageResponse<MyExpenseClaim> = {
  content: [],
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0,
}

/** GET /api/v1/me/expense-claims?page=&size= — the caller's own claims, paginated. */
export function useMyClaims(
  params: TenantParams & { page?: number; size?: number; enabled: boolean },
) {
  const { companyId, actor, page, size, enabled } = params
  const boundedPage = page ?? 0
  const boundedSize = size ?? 25
  return useQuery({
    enabled,
    queryKey: ['myExpenseClaims', companyId, boundedPage, boundedSize],
    queryFn: async () => {
      const result = await apiFetch<PageResponse<MyExpenseClaim>>('/api/v1/me/expense-claims', {
        tenant: { companyId, actor },
        query: { page: boundedPage.toString(), size: boundedSize.toString() },
      })
      return result ?? EMPTY_PAGE
    },
  })
}

/** The full detail shape (mirrors ExpenseClaimResponse). */
export interface ExpenseClaimDetail {
  id: string
  employeeId: string
  categoryId: string
  orgUnitId: string
  status: ClaimStatus
  amountMinor: number
  currency: string
  expenseDate: string
  merchant: string | null
  note: string | null
  reimbursementMethod: ReimbursementMethod
  reimbursementRunId: string | null
  settledAt: string | null
  approvedAt: string | null
  decidedBy: string | null
  decidedAt: string | null
  decisionComment: string | null
}

/** GET /api/v1/me/expense-claims/{id}. */
export function useMyClaim(params: TenantParams & { id: string | null; enabled: boolean }) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled: enabled && !!id,
    queryKey: ['myExpenseClaim', companyId, id],
    queryFn: () =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/me/expense-claims/${id}`, {
        tenant: { companyId, actor },
      }),
  })
}

// ---------------------------------------------------------------------------
// Manager surface — the tenant-wide claim list/detail + decisions (Phase E7)
// ---------------------------------------------------------------------------

/** One row of the manager-facing claim list (mirrors ExpenseClaimSummaryResponse). */
export interface ExpenseClaimSummary {
  id: string
  employeeId: string
  employeeName: string
  status: ClaimStatus
  amountMinor: number
  currency: string
  expenseDate: string
  merchant: string | null
  categoryName: string
  orgUnitId: string
  reimbursementMethod: ReimbursementMethod
  decidedBy: string | null
  decidedAt: string | null
  decisionComment: string | null
}

const EMPTY_MANAGER_PAGE: PageResponse<ExpenseClaimSummary> = {
  content: [],
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0,
}

/**
 * GET /api/v1/expense-claims?status=&orgUnitId=&page=&size= — the tenant-wide claim list,
 * SUBMITTED-first (pending work can never fall off the end of a page). `status` is an exact
 * {@link ClaimStatus} filter (omit for every status). `orgUnitId` is bound server-side to a
 * `List<UUID>` — the manager filter dropdown (ExpensesList) passes one id, but the org-unit hub's
 * recent-claims panel (OrgUnitExpensesTab) passes a comma-joined multi-id BU scope, the SAME
 * `useEmployees` idiom {@link useOrgUnitExpenseSummary} also uses.
 */
export function useClaims(
  params: TenantParams & {
    status?: ClaimStatus
    orgUnitId?: string
    page?: number
    size?: number
    enabled: boolean
  },
) {
  const { companyId, actor, status, orgUnitId, page, size, enabled } = params
  const boundedPage = page ?? 0
  const boundedSize = size ?? 25
  return useQuery({
    enabled,
    queryKey: ['expenseClaims', companyId, status ?? '', orgUnitId ?? '', boundedPage, boundedSize],
    queryFn: async () => {
      const result = await apiFetch<PageResponse<ExpenseClaimSummary>>('/api/v1/expense-claims', {
        tenant: { companyId, actor },
        auth: 'personal',
        query: {
          status,
          orgUnitId,
          page: boundedPage.toString(),
          size: boundedSize.toString(),
        },
      })
      return result ?? EMPTY_MANAGER_PAGE
    },
  })
}

/** GET /api/v1/expense-claims/{id} — any claim visible in the bound tenant. */
export function useClaim(params: TenantParams & { id: string | null; enabled: boolean }) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled: enabled && !!id,
    queryKey: ['expenseClaim', companyId, id],
    queryFn: () =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/expense-claims/${id}`, {
        tenant: { companyId, actor },
        auth: 'personal',
      }),
  })
}

// ---------------------------------------------------------------------------
// Org-unit hub rollup (Phase E8) — the Expenses tab's summary tiles + breakdown.
// ---------------------------------------------------------------------------

/** One category's recognised (APPROVED/REIMBURSED) spend (mirrors OrgUnitExpenseSummaryResponse.CategoryTotal). */
export interface ExpenseCategoryTotal {
  categoryName: string
  totalMinor: number
  currency: string
}

/** One status's claim count (mirrors OrgUnitExpenseSummaryResponse.StatusCount). */
export interface ExpenseStatusCount {
  status: ClaimStatus
  count: number
}

/** GET /api/v1/expense-claims/summary response (mirrors OrgUnitExpenseSummaryResponse). */
export interface OrgUnitExpenseSummary {
  byCategory: ExpenseCategoryTotal[]
  byStatus: ExpenseStatusCount[]
  approvedReimbursedTotalMinor: number
  currency: string | null
}

const EMPTY_SUMMARY: OrgUnitExpenseSummary = {
  byCategory: [],
  byStatus: [],
  approvedReimbursedTotalMinor: 0,
  currency: null,
}

/**
 * GET /api/v1/expense-claims/summary?orgUnitIds=&period= — the org-unit hub's Expenses tab rollup:
 * per-category spend (APPROVED/REIMBURSED only — the recognised subset), claim counts by status,
 * and the approved+reimbursed grand total. `orgUnitIds` follows the `useEmployees` BU-rollup idiom
 * (features/hr/api.ts): the caller passes [buId, ...childOutletIds] from the org tree it already
 * has, joined into one comma-separated value the server's `List<UUID>` param binds transparently —
 * empty/omitted = the whole tenant. `period` is an optional `YYYY-MM` filter.
 */
export function useOrgUnitExpenseSummary(
  params: TenantParams & { orgUnitIds: string[]; period?: string; enabled: boolean },
) {
  const { companyId, actor, orgUnitIds, period, enabled } = params
  const scope = orgUnitIds.join(',')
  return useQuery({
    enabled,
    queryKey: ['orgUnitExpenseSummary', companyId, scope, period ?? ''],
    queryFn: async () => {
      const result = await apiFetch<OrgUnitExpenseSummary>('/api/v1/expense-claims/summary', {
        tenant: { companyId, actor },
        auth: 'personal',
        query: { orgUnitIds: scope || undefined, period },
      })
      return result ?? EMPTY_SUMMARY
    },
  })
}

function invalidateManagerClaims(
  queryClient: ReturnType<typeof useQueryClient>,
  companyId: string,
  id?: string,
) {
  void queryClient.invalidateQueries({ queryKey: ['expenseClaims', companyId] })
  if (id) {
    void queryClient.invalidateQueries({ queryKey: ['expenseClaim', companyId, id] })
  }
}

/** Mutation variables shared by every guarded manager transition — see `ClaimTransitionVariables`
 *  for why `idempotencyKey` must be minted ONCE per submit, in the calling component. */
export interface ApproveClaimVariables {
  id: string
  comment?: string
  idempotencyKey: string
}

/** POST /api/v1/expense-claims/{id}/approve — SUBMITTED -> APPROVED. Comment optional. */
export function useApproveClaim(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, comment, idempotencyKey }: ApproveClaimVariables) =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/expense-claims/${id}/approve`, {
        method: 'POST',
        tenant: { companyId, actor },
        auth: 'personal',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: { comment },
      }),
    onSuccess: (_, { id }) => invalidateManagerClaims(queryClient, companyId, id),
  })
}

export interface RefuseClaimVariables {
  id: string
  comment: string
  idempotencyKey: string
}

/** POST /api/v1/expense-claims/{id}/refuse — SUBMITTED -> REFUSED. Comment required. */
export function useRefuseClaim(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, comment, idempotencyKey }: RefuseClaimVariables) =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/expense-claims/${id}/refuse`, {
        method: 'POST',
        tenant: { companyId, actor },
        auth: 'personal',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: { comment },
      }),
    onSuccess: (_, { id }) => invalidateManagerClaims(queryClient, companyId, id),
  })
}

/** POST /api/v1/expense-claims/{id}/pay — APPROVED + unlinked -> REIMBURSED (DIRECT). */
export function usePayClaimNow(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, idempotencyKey }: ClaimTransitionVariables) =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/expense-claims/${id}/pay`, {
        method: 'POST',
        tenant: { companyId, actor },
        auth: 'personal',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: (_, { id }) => invalidateManagerClaims(queryClient, companyId, id),
  })
}

export interface VoidClaimVariables {
  id: string
  comment: string
  idempotencyKey: string
}

/** POST /api/v1/expense-claims/{id}/void — APPROVED + unlinked + unsettled -> VOIDED. */
export function useVoidClaim(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, comment, idempotencyKey }: VoidClaimVariables) =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/expense-claims/${id}/void`, {
        method: 'POST',
        tenant: { companyId, actor },
        auth: 'personal',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: { comment },
      }),
    onSuccess: (_, { id }) => invalidateManagerClaims(queryClient, companyId, id),
  })
}

function invalidateClaims(
  queryClient: ReturnType<typeof useQueryClient>,
  companyId: string,
  id?: string,
) {
  // Broad invalidation (not just the current page's key) — a create/edit/submit/cancel can shift
  // which page a row belongs to (status-ordered manager lists aside, the caller's own list is
  // newest-updated first), so every cached page for this company is treated as stale.
  void queryClient.invalidateQueries({ queryKey: ['myExpenseClaims', companyId] })
  if (id) {
    void queryClient.invalidateQueries({ queryKey: ['myExpenseClaim', companyId, id] })
  }
}

// ---------------------------------------------------------------------------
// Create / edit-draft
// ---------------------------------------------------------------------------

/** POST /api/v1/me/expense-claims body, and PUT .../{id}'s full-replace body (shared shape). */
export interface ClaimBody {
  categoryId: string
  amountMinor: number
  currency: string
  expenseDate: string
  merchant?: string
  note?: string
  reimbursementMethod?: ReimbursementMethod
}

/** POST /api/v1/me/expense-claims — create a DRAFT claim. */
export function useCreateClaim(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ClaimBody) =>
      apiFetch<ExpenseClaimDetail>('/api/v1/me/expense-claims', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => invalidateClaims(queryClient, companyId),
  })
}

/** PUT /api/v1/me/expense-claims/{id} — full replace of a DRAFT claim's editable fields. */
export function useUpdateDraft(params: TenantParams & { id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ClaimBody) =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/me/expense-claims/${id}`, {
        method: 'PUT',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => invalidateClaims(queryClient, companyId, id),
  })
}

// ---------------------------------------------------------------------------
// Submit / cancel — guarded transitions, Idempotency-Key required
// ---------------------------------------------------------------------------

/**
 * Mutation variables for {@link useSubmitClaim} / {@link useCancelClaim}. `idempotencyKey` MUST be
 * generated ONCE per user click (`crypto.randomUUID()` in the calling component's click handler)
 * and passed in here — NOT minted inside `mutationFn`, which re-runs on every TanStack Query retry
 * and would mint a fresh key per retry, defeating the backend's retry de-dupe (the
 * `RecordPaymentVariables` idiom, features/ar/api.ts).
 */
export interface ClaimTransitionVariables {
  id: string
  idempotencyKey: string
}

/** POST /api/v1/me/expense-claims/{id}/submit — DRAFT -> SUBMITTED. Requires Idempotency-Key. */
export function useSubmitClaim(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, idempotencyKey }: ClaimTransitionVariables) =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/me/expense-claims/${id}/submit`, {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: (_, { id }) => invalidateClaims(queryClient, companyId, id),
  })
}

/** POST /api/v1/me/expense-claims/{id}/cancel — DRAFT/SUBMITTED -> CANCELLED. Requires Idempotency-Key. */
export function useCancelClaim(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, idempotencyKey }: ClaimTransitionVariables) =>
      apiFetch<ExpenseClaimDetail>(`/api/v1/me/expense-claims/${id}/cancel`, {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: (_, { id }) => invalidateClaims(queryClient, companyId, id),
  })
}

// ---------------------------------------------------------------------------
// Receipt upload + preview (Phase E3 endpoints; first console consumer)
// ---------------------------------------------------------------------------

/** POST .../receipt response (mirrors ReceiptMetaResponse) — metadata only, never the bytes. */
export interface ReceiptMeta {
  id: string
  claimId: string
  contentType: string
  byteSize: number
  sha256: string
}

/**
 * POST /api/v1/me/expense-claims/{id}/receipt (multipart) — upload or replace the receipt photo
 * for the caller's own DRAFT claim. Deliberately NOT Idempotency-Key guarded (replace-idempotent by
 * nature, per the server-side `ReceiptWriter` Javadoc — mirrors `apiUpload`'s own doc).
 */
export function useUploadReceipt(params: TenantParams) {
  const { companyId, actor } = params
  return useMutation({
    mutationFn: ({ id, file }: { id: string; file: File }) => {
      const formData = new FormData()
      formData.append('file', file)
      return apiUpload<ReceiptMeta>(`/api/v1/me/expense-claims/${id}/receipt`, formData, {
        tenant: { companyId, actor },
      })
    },
  })
}

export type ReceiptPreviewStatus = 'idle' | 'loading' | 'ready' | 'none' | 'error'

interface ReceiptPreviewState {
  url: string | null
  status: ReceiptPreviewStatus
}

const RECEIPT_IDLE: ReceiptPreviewState = { url: null, status: 'idle' }

/**
 * Fetches a receipt at `path` as an object URL for an `<img>` preview. Deliberately NOT a TanStack
 * Query: a Blob's object-URL lifetime needs an explicit revoke tied to THIS component instance's
 * mount/param changes, which a shared query cache would complicate (a second mount reusing a
 * cached Blob after the first mount revoked its URL). A 404 (no receipt on file yet) resolves to
 * the `'none'` status, not `'error'` — an expense claim legitimately may have none.
 *
 * `url` and `status` are ONE state value (not two separate `useState` calls) updated with a single
 * `setState` per branch — avoids the cascading-render pattern of setting two pieces of state back
 * to back inside an effect. When disabled/no path, the hook returns the idle constant directly
 * (never runs the effect body at all) rather than resetting state from inside the effect.
 *
 * Shared by {@link useMyReceiptUrl} (the employee `/me` surface, ME_ROLES — stays on the outlet
 * bearer, `auth` defaults to `'outlet'`) and {@link useManagerReceiptUrl} (the manager surface,
 * Phase E7, DASHBOARD_ROLES — passes `auth: 'personal'`, ADR 0049 P3b) — same lifecycle, different
 * endpoint + bearer.
 */
function useReceiptUrlAtPath(
  path: string | null,
  enabled: boolean,
  companyId: string,
  actor: string,
  auth: AuthTarget = 'outlet',
) {
  const active = enabled && !!path
  const [state, setState] = useState<ReceiptPreviewState>(RECEIPT_IDLE)

  useEffect(() => {
    if (!active || !path) return undefined

    let objectUrl: string | null = null
    let cancelled = false

    void (async () => {
      setState({ url: null, status: 'loading' })
      try {
        const blob = await apiFetchBlob(path, { tenant: { companyId, actor }, auth })
        if (cancelled) return
        if (!blob) {
          setState({ url: null, status: 'none' })
          return
        }
        objectUrl = URL.createObjectURL(blob)
        setState({ url: objectUrl, status: 'ready' })
      } catch (err) {
        if (cancelled) return
        setState({ url: null, status: err instanceof ApiError && err.status === 404 ? 'none' : 'error' })
      }
    })()

    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [companyId, actor, path, active, auth])

  return active ? state : RECEIPT_IDLE
}

/** GET /api/v1/me/expense-claims/{id}/receipt — the caller's own claim (employee surface). */
export function useMyReceiptUrl(params: TenantParams & { id: string | null; enabled: boolean }) {
  const { companyId, actor, id, enabled } = params
  const path = id ? `/api/v1/me/expense-claims/${id}/receipt` : null
  return useReceiptUrlAtPath(path, enabled, companyId, actor)
}

/** GET /api/v1/expense-claims/{id}/receipt — any claim in the tenant (manager surface, Phase E7). */
export function useManagerReceiptUrl(params: TenantParams & { id: string | null; enabled: boolean }) {
  const { companyId, actor, id, enabled } = params
  const path = id ? `/api/v1/expense-claims/${id}/receipt` : null
  return useReceiptUrlAtPath(path, enabled, companyId, actor, 'personal')
}
