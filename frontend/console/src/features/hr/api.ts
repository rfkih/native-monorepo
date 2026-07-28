/**
 * HR + payroll data plumbing (employee-service, gateway-routed, owner/manager only).
 *
 * Employees are HR RECORDS (a chef may never log in) — deliberately separate from the Team page's
 * Keycloak login users. Salary is PII: every compensation/payslip read arrives MASKED ("***") and
 * no hook here ever sees a plaintext amount besides the one the owner types into the create form.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

// ---------------------------------------------------------------------------
// Types (mirrors of employee-service DTOs)
// ---------------------------------------------------------------------------

/** One row of GET /api/v1/employees — an employee × ONE current assignment in scope. */
export interface EmployeeListRow {
  employeeId: string
  fullName: string
  status: 'ACTIVE' | 'INACTIVE'
  ptkpStatus: string
  /** The linked console login's Keycloak subject id, or null when the employee has no login. */
  userId: string | null
  assignmentId: string | null
  orgUnitId: string | null
  role: string | null
  reportingTo: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  hasCompensation: boolean
}

/** GET /api/v1/employees/{id} — employee (PII masked) + assignment history + contracts. */
export interface EmployeeDetail {
  employee: {
    id: string
    fullName: string
    ptkpStatus: string
    status: string
    maskedNik: string
    maskedBankAccount: string
    userId: string | null
  }
  assignments: AssignmentRow[]
  contracts: ContractRow[]
}

export interface AssignmentRow {
  id: string
  employeeId: string
  orgUnitId: string
  reportingTo: string | null
  role: string
  effectiveFrom: string
  effectiveTo: string
}

export interface ContractRow {
  id: string
  employmentType: string
  legalEmployerId: string
  effectiveFrom: string
  effectiveTo: string
}

/** GET /api/v1/employees/org-units — legal-employer lookup from the local org read model. */
export interface OrgUnitLookupRow {
  orgUnitId: string
  legalEmployerId: string
  type: string
  active: boolean
}

/** Compensation rows are ALWAYS masked (salary PII). */
export interface CompensationRow {
  id: string
  employmentContractId: string
  payFrequency: string
  effectiveFrom: string
  effectiveTo: string
  amountMasked: string
}

export interface PayrollSetup {
  seeded: boolean
  componentCount: number
  provenance: 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL' | 'MIXED' | null
  illustrativeVersion: string | null
}

export interface PayrollRunSummary {
  id: string
  period: string
  runSeq: number
  status: string
  baseCurrency: string
  grossTotalMinor: number
  employeeDeductionTotalMinor: number
  employerContributionTotalMinor: number
  netTotalMinor: number
  usesIllustrativeRules: boolean
  postedAt: string | null
}

export interface AllocationSummaryRow {
  outletOrgUnitId: string
  glAccount: string
  amountMinor: number
  currency: string
  unallocated: boolean
}

export interface PayslipIndexRow {
  employeeId: string
  fullName: string
  lineCount: number
  illustrative: boolean
}

/** Masked payslip line — amountMasked is "***"; amountMinor/currency are null when masked. */
export interface PayslipLine {
  componentKey: string
  kind: string
  bearer: string
  glAccount: string
  amountMasked: string | null
  amountMinor: number | null
  currency: string | null
  ruleVersion: string | null
  illustrative: boolean
}

/** The open-ended effective_to sentinel (matches the backend convention). */
export const OPEN_ENDED = '9999-12-31'

/** Suggested job roles (free text is allowed — the backend stores any ≤128-char string). */
export const ROLE_PRESETS = [
  'chef',
  'waiter',
  'cashier',
  'barista',
  'admin',
  'manager',
  'shift_lead',
  'cook',
  'host',
] as const

interface TenantParams {
  companyId: string
  actor: string
}

// ---------------------------------------------------------------------------
// Employee queries
// ---------------------------------------------------------------------------

/**
 * GET /api/v1/employees?orgUnitIds= — the unit-scoped HR list. The BU rollup is CLIENT-computed:
 * the caller passes [buId, ...childOutletIds] from the org tree it already has (the projection has
 * no parent_id). Empty orgUnitIds = the whole tenant.
 */
export function useEmployees(
  params: TenantParams & { orgUnitIds: string[]; enabled: boolean },
) {
  const { companyId, actor, orgUnitIds, enabled } = params
  const scope = orgUnitIds.join(',')
  return useQuery({
    enabled,
    queryKey: ['hrEmployees', companyId, scope],
    queryFn: async () => {
      const qs = scope ? `?orgUnitIds=${encodeURIComponent(scope)}` : ''
      const result = await apiFetch<EmployeeListRow[]>(`/api/v1/employees${qs}`, {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/employees/{id} — detail with assignment history (PII masked). */
export function useEmployee(
  params: TenantParams & { employeeId: string | null; enabled: boolean },
) {
  const { companyId, actor, employeeId, enabled } = params
  return useQuery({
    enabled: enabled && !!employeeId,
    queryKey: ['hrEmployee', companyId, employeeId],
    queryFn: () =>
      apiFetch<EmployeeDetail>(`/api/v1/employees/${employeeId}`, {
        tenant: { companyId, actor },
      }),
  })
}

/** GET /api/v1/employees/org-units?ids= — legal employers for contract creation. */
export function useOrgUnitLookup(
  params: TenantParams & { ids: string[]; enabled: boolean },
) {
  const { companyId, actor, ids, enabled } = params
  const key = ids.join(',')
  return useQuery({
    enabled: enabled && ids.length > 0,
    queryKey: ['hrOrgUnitLookup', companyId, key],
    queryFn: async () => {
      const result = await apiFetch<OrgUnitLookupRow[]>(
        `/api/v1/employees/org-units?ids=${encodeURIComponent(key)}`,
        { tenant: { companyId, actor } },
      )
      return result ?? []
    },
  })
}

// ---------------------------------------------------------------------------
// Employee mutations (each invalidates the HR list; detail invalidated where relevant)
// ---------------------------------------------------------------------------

function useHrInvalidate(companyId: string) {
  const queryClient = useQueryClient()
  return (employeeId?: string) => {
    void queryClient.invalidateQueries({ queryKey: ['hrEmployees', companyId] })
    if (employeeId) {
      void queryClient.invalidateQueries({ queryKey: ['hrEmployee', companyId, employeeId] })
    }
  }
}

export function useCreateEmployee(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: (body: { fullName: string; ptkpStatus: string; nik: string; bankAccount: string }) =>
      apiFetch<EmployeeDetail['employee']>('/api/v1/employees', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => invalidate(),
  })
}

export function useUpdateEmployee(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      body,
    }: {
      employeeId: string
      body: { fullName?: string; ptkpStatus?: string; status?: string }
    }) =>
      apiFetch<EmployeeDetail['employee']>(`/api/v1/employees/${employeeId}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: (_d, { employeeId }) => invalidate(employeeId),
  })
}

export function useAddContract(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      body,
    }: {
      employeeId: string
      body: { employmentType: string; legalEmployerId: string; effectiveFrom: string }
    }) =>
      apiFetch<ContractRow>(`/api/v1/employees/${employeeId}/contracts`, {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: (_d, { employeeId }) => invalidate(employeeId),
  })
}

export function useAddAssignment(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      body,
    }: {
      employeeId: string
      body: { orgUnitId: string; role: string; effectiveFrom: string; reportingTo?: string }
    }) =>
      apiFetch<AssignmentRow>(`/api/v1/employees/${employeeId}/assignments`, {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: (_d, { employeeId }) => invalidate(employeeId),
  })
}

/** POST /api/v1/employees/{id}/login-link — attach a console login (Keycloak sub) to an employee. */
export function useLinkLogin(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({ employeeId, userId }: { employeeId: string; userId: string }) =>
      apiFetch<EmployeeDetail['employee']>(`/api/v1/employees/${employeeId}/login-link`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { userId },
      }),
    onSuccess: (_d, { employeeId }) => invalidate(employeeId),
  })
}

export function useEndAssignment(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      assignmentId,
      endOn,
    }: {
      employeeId: string
      assignmentId: string
      endOn: string
    }) =>
      apiFetch<AssignmentRow>(`/api/v1/employees/${employeeId}/assignments/${assignmentId}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        body: { endOn },
      }),
    onSuccess: (_d, { employeeId }) => invalidate(employeeId),
  })
}

// ---------------------------------------------------------------------------
// Compensation (always masked)
// ---------------------------------------------------------------------------

export function useCompensation(
  params: TenantParams & { employeeId: string | null; enabled: boolean },
) {
  const { companyId, actor, employeeId, enabled } = params
  return useQuery({
    enabled: enabled && !!employeeId,
    queryKey: ['hrCompensation', companyId, employeeId],
    queryFn: async () => {
      const result = await apiFetch<CompensationRow[]>(
        `/api/v1/employees/${employeeId}/compensation`,
        { tenant: { companyId, actor } },
      )
      return result ?? []
    },
  })
}

export function useCreateCompensation(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      body,
    }: {
      employeeId: string
      body: {
        employmentContractId: string
        basePayMinor: number
        currency: string
        effectiveFrom: string
      }
    }) =>
      apiFetch<CompensationRow>(`/api/v1/employees/${employeeId}/compensation`, {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: (_d, { employeeId }) => {
      invalidate(employeeId)
      void queryClient.invalidateQueries({ queryKey: ['hrCompensation', companyId, employeeId] })
    },
  })
}

export function useEndCompensation(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      packageId,
      endOn,
    }: {
      employeeId: string
      packageId: string
      endOn: string
    }) =>
      apiFetch<CompensationRow>(`/api/v1/employees/${employeeId}/compensation/${packageId}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        body: { endOn },
      }),
    onSuccess: (_d, { employeeId }) => {
      invalidate(employeeId)
      void queryClient.invalidateQueries({ queryKey: ['hrCompensation', companyId, employeeId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Commission (own-sales; non-PII config — real basis points)
// ---------------------------------------------------------------------------

export interface CommissionRow {
  id: string
  metricKey: string
  percentBasisPoints: number
  effectiveFrom: string
  effectiveTo: string
}

export function useCommissions(
  params: TenantParams & { employeeId: string; packageId: string | null; enabled: boolean },
) {
  const { companyId, actor, employeeId, packageId, enabled } = params
  return useQuery({
    enabled: enabled && !!packageId,
    queryKey: ['hrCommission', companyId, employeeId, packageId],
    queryFn: async () => {
      const result = await apiFetch<CommissionRow[]>(
        `/api/v1/employees/${employeeId}/compensation/${packageId}/commission`,
        { tenant: { companyId, actor } },
      )
      return result ?? []
    },
  })
}

export function useSetCommission(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      employeeId,
      packageId,
      percentBasisPoints,
    }: {
      employeeId: string
      packageId: string
      percentBasisPoints: number
    }) =>
      apiFetch<CommissionRow>(
        `/api/v1/employees/${employeeId}/compensation/${packageId}/commission`,
        {
          method: 'POST',
          tenant: { companyId, actor },
          body: { percentBasisPoints, metricKey: 'sales_amount' },
        },
      ),
    onSuccess: (_d, { employeeId, packageId }) => {
      void queryClient.invalidateQueries({
        queryKey: ['hrCommission', companyId, employeeId, packageId],
      })
    },
  })
}

export function useEndCommission(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      employeeId,
      packageId,
      ruleId,
    }: {
      employeeId: string
      packageId: string
      ruleId: string
    }) =>
      apiFetch<CommissionRow>(
        `/api/v1/employees/${employeeId}/compensation/${packageId}/commission/${ruleId}`,
        { method: 'DELETE', tenant: { companyId, actor } },
      ),
    onSuccess: (_d, { employeeId, packageId }) => {
      void queryClient.invalidateQueries({
        queryKey: ['hrCommission', companyId, employeeId, packageId],
      })
    },
  })
}

// ---------------------------------------------------------------------------
// Payroll setup + runs
// ---------------------------------------------------------------------------

export function usePayrollSetup(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['payrollSetup', companyId],
    queryFn: () =>
      apiFetch<PayrollSetup>('/api/v1/payroll-setup', { tenant: { companyId, actor } }),
  })
}

export function useSeedIllustrative(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (baseCurrency: string) =>
      apiFetch<PayrollSetup>('/api/v1/payroll-setup/seed-illustrative', {
        method: 'POST',
        tenant: { companyId, actor },
        body: { baseCurrency },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['payrollSetup', companyId] })
    },
  })
}

export function usePayrollRuns(
  params: TenantParams & { period: string; enabled: boolean },
) {
  const { companyId, actor, period, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['payrollRuns', companyId, period],
    queryFn: async () => {
      const result = await apiFetch<PayrollRunSummary[]>(
        `/api/v1/payroll-runs?period=${encodeURIComponent(period)}`,
        { tenant: { companyId, actor } },
      )
      return result ?? []
    },
  })
}

export function useRunPayroll(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      period: string
      employeeIds: string[]
      expectedSourceBusinessIds: string[]
      baseCurrency: string
    }) =>
      apiFetch<PayrollRunSummary>('/api/v1/payroll-runs', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: (_d, { period }) => {
      void queryClient.invalidateQueries({ queryKey: ['payrollRuns', companyId, period] })
    },
  })
}

export function useRunAllocations(
  params: TenantParams & { runId: string | null; enabled: boolean },
) {
  const { companyId, actor, runId, enabled } = params
  return useQuery({
    enabled: enabled && !!runId,
    queryKey: ['payrollAllocations', companyId, runId],
    queryFn: async () => {
      const result = await apiFetch<AllocationSummaryRow[]>(
        `/api/v1/payroll-runs/${runId}/allocations`,
        { tenant: { companyId, actor } },
      )
      return result ?? []
    },
  })
}

export function usePayslipIndex(
  params: TenantParams & { runId: string | null; enabled: boolean },
) {
  const { companyId, actor, runId, enabled } = params
  return useQuery({
    enabled: enabled && !!runId,
    queryKey: ['payslipIndex', companyId, runId],
    queryFn: async () => {
      const result = await apiFetch<PayslipIndexRow[]>(`/api/v1/payroll-runs/${runId}/payslips`, {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

export function usePayslip(
  params: TenantParams & { runId: string | null; employeeId: string | null; enabled: boolean },
) {
  const { companyId, actor, runId, employeeId, enabled } = params
  return useQuery({
    enabled: enabled && !!runId && !!employeeId,
    queryKey: ['payslip', companyId, runId, employeeId],
    queryFn: async () => {
      const result = await apiFetch<PayslipLine[]>(
        `/api/v1/payroll-runs/${runId}/payslips/${employeeId}`,
        { tenant: { companyId, actor } },
      )
      return result ?? []
    },
  })
}
