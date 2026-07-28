/**
 * Employee self-service data plumbing (/api/v1/me). The backend resolves the caller from the
 * verified token, so these hooks send NO identity — just the bearer (oidc) or the dev headers.
 * Payslip amounts are the caller's OWN real figures; NIK/bank stay masked (rule 6).
 */

import { useQuery } from '@tanstack/react-query'
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
