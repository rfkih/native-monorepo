/**
 * Employee self-service data plumbing (/api/v1/me). The backend resolves the caller from the
 * verified token, so these hooks send NO identity — just the bearer (oidc) or the dev headers.
 * Payslip amounts are the caller's OWN real figures; NIK/bank stay masked (rule 6).
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/api'

export interface MeAssignment {
  id: string
  orgUnitId: string
  role: string
  effectiveFrom: string
  effectiveTo: string
}

export interface MeContract {
  id: string
  employmentType: string
  legalEmployerId: string
  effectiveFrom: string
  effectiveTo: string
}

export interface MeProfile {
  employeeId: string
  fullName: string
  ptkpStatus: string
  status: string
  maskedNik: string
  maskedBankAccount: string
  assignments: MeAssignment[]
  contracts: MeContract[]
}

export interface MyPayslipHeader {
  runId: string
  period: string
  runSeq: number
  postedAt: string | null
  lineCount: number
  illustrative: boolean
}

export interface MyPayslipLine {
  componentKey: string
  kind: string
  bearer: string
  amountMinor: number
  currency: string
  illustrative: boolean
}

export interface MyPayslipDetail {
  runId: string
  period: string
  runSeq: number
  currency: string
  grossMinor: number
  deductionMinor: number
  netMinor: number
  illustrative: boolean
  lines: MyPayslipLine[]
}

export interface MySales {
  period: string
  salesMinor: number
  currency: string
  commissionBasisPoints: number | null
  commissionEstimateMinor: number | null
}

interface TenantParams {
  companyId: string
  actor: string
}

/** True when a /me read returned the not-linked 404 (the login has no employee link yet). */
export function isNotLinked(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.problem?.type === 'https://errors.nativeapp.id/employee-not-linked'
  )
}

/** GET /api/v1/me/profile — own record (PII masked) with assignments + contracts. */
export function useMyProfile(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    retry: false,
    queryKey: ['meProfile', companyId],
    queryFn: () => apiFetch<MeProfile>('/api/v1/me/profile', { tenant: { companyId, actor } }),
  })
}

/** GET /api/v1/me/payslips — own payslip index (no amounts). */
export function useMyPayslips(params: TenantParams & { period?: string; enabled: boolean }) {
  const { companyId, actor, period, enabled } = params
  return useQuery({
    enabled,
    retry: false,
    queryKey: ['myPayslips', companyId, period ?? 'all'],
    queryFn: async () => {
      const qs = period ? `?period=${encodeURIComponent(period)}` : ''
      const result = await apiFetch<MyPayslipHeader[]>(`/api/v1/me/payslips${qs}`, {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/me/payslips/{runId} — own payslip detail with REAL amounts. */
export function useMyPayslip(
  params: TenantParams & { runId: string | null; enabled: boolean },
) {
  const { companyId, actor, runId, enabled } = params
  return useQuery({
    enabled: enabled && !!runId,
    retry: false,
    queryKey: ['myPayslip', companyId, runId],
    queryFn: () =>
      apiFetch<MyPayslipDetail>(`/api/v1/me/payslips/${runId}`, { tenant: { companyId, actor } }),
  })
}

/** GET /api/v1/me/sales — own sales + commission preview for the current month. */
export function useMySales(params: TenantParams & { period?: string; enabled: boolean }) {
  const { companyId, actor, period, enabled } = params
  return useQuery({
    enabled,
    retry: false,
    queryKey: ['mySales', companyId, period ?? 'current'],
    queryFn: () => {
      const qs = period ? `?period=${encodeURIComponent(period)}` : ''
      return apiFetch<MySales>(`/api/v1/me/sales${qs}`, { tenant: { companyId, actor } })
    },
  })
}

// ---------------------------------------------------------------------------
// My time off — self-service (ADR 0033, Track P Phase P6)
// ---------------------------------------------------------------------------

export type TimeoffStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** GET /api/v1/me/leave-requests row. */
export interface MyLeaveRequest {
  id: string
  leaveType: 'ANNUAL' | 'UNPAID' | 'SICK'
  startDate: string
  endDate: string
  days: number
  status: TimeoffStatus
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

/** GET /api/v1/me/overtime-entries row. */
export interface MyOvertimeEntry {
  id: string
  workDate: string
  minutes: number
  dayKind: 'WEEKDAY' | 'REST_DAY'
  status: TimeoffStatus
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

/** GET /api/v1/me/leave-balance — the caller's own DERIVED annual-leave balance for a year. */
export interface MyLeaveBalance {
  year: number
  grantedDays: number
  adjustmentDays: number
  usedDays: number
  remaining: number
}

/** GET /api/v1/me/leave-requests — the caller's own requests (paginated). */
export function useMyLeaveRequests(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    retry: false,
    queryKey: ['myLeaveRequests', companyId],
    queryFn: async () => {
      const result = await apiFetch<PageResponse<MyLeaveRequest>>('/api/v1/me/leave-requests', {
        tenant: { companyId, actor },
      })
      return result?.content ?? []
    },
  })
}

/** GET /api/v1/me/leave-balance?year= — the caller's own derived balance. */
export function useMyLeaveBalance(params: TenantParams & { year: number; enabled: boolean }) {
  const { companyId, actor, year, enabled } = params
  return useQuery({
    enabled,
    retry: false,
    queryKey: ['myLeaveBalance', companyId, year],
    queryFn: () =>
      apiFetch<MyLeaveBalance>('/api/v1/me/leave-balance', {
        tenant: { companyId, actor },
        query: { year: year.toString() },
      }),
  })
}

/** POST /api/v1/me/leave-requests — submits a new leave request (create IS the submit transition). */
export function useCreateLeaveRequest(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      body,
      idempotencyKey,
    }: {
      body: { leaveType: string; startDate: string; endDate: string; days: number }
      idempotencyKey: string
    }) =>
      apiFetch<MyLeaveRequest>('/api/v1/me/leave-requests', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['myLeaveRequests', companyId] })
      void queryClient.invalidateQueries({ queryKey: ['myLeaveBalance', companyId] })
    },
  })
}

/** POST /api/v1/me/leave-requests/{id}/cancel */
export function useCancelLeaveRequest(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ requestId, idempotencyKey }: { requestId: string; idempotencyKey: string }) =>
      apiFetch<MyLeaveRequest>(`/api/v1/me/leave-requests/${requestId}/cancel`, {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['myLeaveRequests', companyId] })
      void queryClient.invalidateQueries({ queryKey: ['myLeaveBalance', companyId] })
    },
  })
}

/** GET /api/v1/me/overtime-entries — the caller's own entries (paginated). */
export function useMyOvertimeEntries(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    retry: false,
    queryKey: ['myOvertimeEntries', companyId],
    queryFn: async () => {
      const result = await apiFetch<PageResponse<MyOvertimeEntry>>('/api/v1/me/overtime-entries', {
        tenant: { companyId, actor },
      })
      return result?.content ?? []
    },
  })
}

/** POST /api/v1/me/overtime-entries — logs a new overtime entry (create IS the submit transition). */
export function useCreateOvertimeEntry(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      body,
      idempotencyKey,
    }: {
      body: { workDate: string; minutes: number; dayKind: string }
      idempotencyKey: string
    }) =>
      apiFetch<MyOvertimeEntry>('/api/v1/me/overtime-entries', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['myOvertimeEntries', companyId] })
    },
  })
}

/** POST /api/v1/me/overtime-entries/{id}/cancel */
export function useCancelOvertimeEntry(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ entryId, idempotencyKey }: { entryId: string; idempotencyKey: string }) =>
      apiFetch<MyOvertimeEntry>(`/api/v1/me/overtime-entries/${entryId}/cancel`, {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['myOvertimeEntries', companyId] })
    },
  })
}
