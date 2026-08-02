/**
 * HR + payroll data plumbing (employee-service, gateway-routed, owner/manager only).
 *
 * Employees are HR RECORDS (a chef may never log in) — deliberately separate from the Team page's
 * Keycloak login users. Salary is PII: every compensation/payslip read arrives MASKED ("***") and
 * no hook here ever sees a plaintext amount besides the one the owner types into the create form.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiDownload, apiFetch } from '@/lib/api'

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

/** GET /api/v1/payroll-setup/rules row — no params (Track P phase P2, ADR 0031). */
export interface StatutoryRuleRow {
  ruleKey: string
  ruleVersion: string
  calcType: string
  provenance: 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL'
  effectiveFrom: string
  effectiveTo: string
  sourceNote: string
  /** Whether this row is the one that actually resolves (a same-day supersession can leave a
   * still-open-looking row that is nonetheless inactive — see StatutoryRule#supersede). */
  active: boolean
}

/** GET /api/v1/payroll-setup/rules/{ruleKey} — the full row including paramsJson. */
export interface StatutoryRuleDetail {
  ruleKey: string
  ruleVersion: string
  calcType: string
  paramsJson: string
  currency: string
  provenance: 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL'
  sourceNote: string
  effectiveFrom: string
  effectiveTo: string
}

/** POST /api/v1/payroll-setup/seed-official response — the idempotent activation summary. */
export interface SeedOfficialSummary {
  datasetVersion: string
  rulesInserted: number
  rulesClosed: number
  rulesSkipped: number
  componentsInserted: number
  componentsUpdated: number
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

/**
 * POST /api/v1/employees/{id}/login-link — attach a console login (Keycloak sub) to an employee,
 * optionally holding the one-time password ENCRYPTED so the owner can hand it over until first
 * sign-in (ADR 0014). Idempotent on the link, so the reset flow reuses it with a fresh password.
 */
export function useLinkLogin(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      userId,
      temporaryPassword,
    }: {
      employeeId: string
      userId: string
      temporaryPassword?: string
    }) =>
      apiFetch<EmployeeDetail['employee']>(`/api/v1/employees/${employeeId}/login-link`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { userId, temporaryPassword },
      }),
    onSuccess: (_d, { employeeId }) => invalidate(employeeId),
  })
}

/** The login state of an employee (GET /api/v1/employees/{id}/login) — owner/manager only. */
export interface EmployeeLogin {
  /** The linked login's Keycloak subject id, or null when the employee has no login. */
  userId: string | null
  /**
   * The decrypted one-time password held for the employee, present ONLY until they first sign in
   * (and within the TTL). A CREDENTIAL — shown once to the owner, never persisted client-side.
   */
  temporaryPassword: string | null
}

/** DELETE /api/v1/employees/{id}/login-link — detach the login (the Keycloak account is untouched). */
export function useUnlinkLogin(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useHrInvalidate(companyId)
  return useMutation({
    mutationFn: ({ employeeId }: { employeeId: string }) =>
      apiFetch<EmployeeDetail['employee']>(`/api/v1/employees/${employeeId}/login-link`, {
        method: 'DELETE',
        tenant: { companyId, actor },
      }),
    onSuccess: (_d, { employeeId }) => invalidate(employeeId),
  })
}

/** GET /api/v1/employees/{id}/login — the employee's login state incl. the held one-time password. */
export function useEmployeeLogin(params: TenantParams & { employeeId: string; enabled: boolean }) {
  const { companyId, actor, employeeId, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['hr', 'employee-login', companyId, employeeId],
    queryFn: () =>
      apiFetch<EmployeeLogin>(`/api/v1/employees/${employeeId}/login`, {
        tenant: { companyId, actor },
      }),
  })
}

/**
 * POST /api/v1/users/{userId}/reset-password — issue a fresh one-time password for a login. Returns
 * it once; the caller then re-holds it on the employee via login-link (ADR 0014).
 */
export function useResetPassword(params: TenantParams) {
  const { companyId, actor } = params
  return useMutation({
    mutationFn: ({ userId }: { userId: string }) =>
      apiFetch<{ userId: string; temporaryPassword: string }>(
        `/api/v1/users/${userId}/reset-password`,
        { method: 'POST', tenant: { companyId, actor } },
      ),
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

// ---------------------------------------------------------------------------
// Statutory rules administration (Track P phase P2, ADR 0031)
// ---------------------------------------------------------------------------

/** GET /api/v1/payroll-setup/rules — the tenant's rules table. */
export function useStatutoryRules(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['statutoryRules', companyId],
    queryFn: async () => {
      const result = await apiFetch<StatutoryRuleRow[]>('/api/v1/payroll-setup/rules', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/payroll-setup/rules/{ruleKey} — the full detail (params JSON) for the drawer. */
export function useStatutoryRuleDetail(
  params: TenantParams & { ruleKey: string | null; enabled: boolean },
) {
  const { companyId, actor, ruleKey, enabled } = params
  return useQuery({
    enabled: enabled && !!ruleKey,
    queryKey: ['statutoryRuleDetail', companyId, ruleKey],
    queryFn: () =>
      apiFetch<StatutoryRuleDetail>(`/api/v1/payroll-setup/rules/${ruleKey}`, {
        tenant: { companyId, actor },
      }),
  })
}

/** POST /api/v1/payroll-setup/seed-official — activate a canned OFFICIAL dataset (idempotent). */
export function useSeedOfficial(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (datasetVersion: string) =>
      apiFetch<SeedOfficialSummary>('/api/v1/payroll-setup/seed-official', {
        method: 'POST',
        tenant: { companyId, actor },
        body: { datasetVersion },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['payrollSetup', companyId] })
      void queryClient.invalidateQueries({ queryKey: ['statutoryRules', companyId] })
    },
  })
}

/** PATCH /api/v1/payroll-setup/rules/{ruleKey} — the human-verification override path. */
export function useOverrideStatutoryRule(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      ruleKey,
      body,
    }: {
      ruleKey: string
      body: {
        paramsJson: string
        effectiveFrom: string
        sourceNote: string
        provenance: 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL'
      }
    }) =>
      apiFetch<StatutoryRuleDetail>(`/api/v1/payroll-setup/rules/${ruleKey}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: (_d, { ruleKey }) => {
      void queryClient.invalidateQueries({ queryKey: ['statutoryRules', companyId] })
      void queryClient.invalidateQueries({
        queryKey: ['statutoryRuleDetail', companyId, ruleKey],
      })
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

// ---------------------------------------------------------------------------
// Payroll liabilities + settlement (finance-service, ADR 0032, Track P phase P5)
// ---------------------------------------------------------------------------

export type SettlementKind = 'NET_WAGES' | 'PPH21' | 'BPJS_KES' | 'BPJS_TK' | 'OTHER'

/** One of the five liability buckets on a GET /api/v1/payroll-liabilities row. */
export interface PayrollLiabilityBucket {
  kind: SettlementKind
  amountMinor: number
  currency: string
  negative: boolean
  settled: boolean
  settledAt: string | null
  journalEntryId: string | null
}

/** GET /api/v1/payroll-liabilities?period= row — one ACTIVE payroll run's liability buckets. */
export interface PayrollLiabilityRun {
  runLedgerId: string
  payrollRunId: string
  period: string
  runSeq: number
  runType: string
  currency: string
  buckets: PayrollLiabilityBucket[]
}

/**
 * GET /api/v1/payroll-liabilities?period= — console HR reads finance-service DIRECTLY (the
 * established cross-service read pattern; finance owns the books). Filtered client-side to the
 * selected run (see the `payrollRunId` field) by the caller.
 */
/** The ENGINEERING-STANDARDS §1.3 pagination envelope (backend review S1, ADR 0032 P5 addendum). */
interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export function usePayrollLiabilities(
  params: TenantParams & { period: string; enabled: boolean },
) {
  const { companyId, actor, period, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['payrollLiabilities', companyId, period],
    queryFn: async () => {
      // The run count for one company/period is small, so the console fetches the default page
      // (size 20) without its own pager UI — every practical case fits on page 0.
      const result = await apiFetch<PageResponse<PayrollLiabilityRun>>(
        `/api/v1/payroll-liabilities?period=${encodeURIComponent(period)}`,
        { tenant: { companyId, actor } },
      )
      return result?.content ?? []
    },
  })
}

/**
 * POST /api/v1/payroll-liabilities/{runLedgerId}/settlements — settle one bucket. The amount is
 * never sent by the client (the server reads it back from the run's own liability entry); the
 * caller supplies a fresh `crypto.randomUUID()` Idempotency-Key per click.
 */
export function useSettlePayrollLiability(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      runLedgerId,
      kind,
      idempotencyKey,
    }: {
      runLedgerId: string
      kind: SettlementKind
      idempotencyKey: string
    }) =>
      apiFetch<PayrollLiabilityRun>(`/api/v1/payroll-liabilities/${runLedgerId}/settlements`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { kind },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['payrollLiabilities', companyId] })
    },
  })
}

/**
 * GET /api/v1/payroll-runs/{runId}/bank-file — triggers a browser download of the net-pay CSV.
 * Owner-only at the gateway (bank-account PII); the caller gates the button client-side too (see
 * `hasAnyRole(auth.roles, 'owner')` in PayrollTab).
 */
export function downloadPayrollBankFile(
  params: TenantParams & { runId: string; period: string; runSeq: number },
): Promise<void> {
  const { companyId, actor, runId, period, runSeq } = params
  return apiDownload(
    `/api/v1/payroll-runs/${runId}/bank-file`,
    { tenant: { companyId, actor } },
    `payroll-${period}-seq${runSeq}.csv`,
  )
}

// ---------------------------------------------------------------------------
// Attendance & time-off — manager surface (ADR 0033, Track P Phase P6)
// ---------------------------------------------------------------------------

export type TimeoffStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

/** GET /api/v1/leave-requests row — the manager-facing tenant-wide list. */
export interface LeaveRequestSummary {
  id: string
  employeeId: string
  employeeName: string
  leaveType: 'ANNUAL' | 'UNPAID' | 'SICK'
  startDate: string
  endDate: string
  days: number
  status: TimeoffStatus
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

/** GET /api/v1/overtime-entries row — the manager-facing tenant-wide list. */
export interface OvertimeEntrySummary {
  id: string
  employeeId: string
  employeeName: string
  workDate: string
  minutes: number
  dayKind: 'WEEKDAY' | 'REST_DAY'
  status: TimeoffStatus
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

/** GET/PUT /api/v1/work-calendar — one row per tenant. */
export interface WorkCalendarRow {
  daysPerWeek: 5 | 6
  monthlyDivisor: 21 | 25
}

/** GET /api/v1/leave-balances row — the per-employee DERIVED annual-leave balance for a year. */
export interface LeaveBalanceRow {
  employeeId: string
  employeeName: string
  year: number
  grantedDays: number
  adjustmentDays: number
  usedDays: number
  remaining: number
}

export function useLeaveRequests(
  params: TenantParams & { status?: string; page?: number; size?: number; enabled: boolean },
) {
  const { companyId, actor, status, page, size, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['leaveRequests', companyId, status ?? 'all', page ?? 0, size ?? 25],
    queryFn: () =>
      apiFetch<PageResponse<LeaveRequestSummary>>('/api/v1/leave-requests', {
        tenant: { companyId, actor },
        query: { status, page: page?.toString(), size: size?.toString() },
      }),
  })
}

function useTimeoffInvalidate(companyId: string) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['leaveRequests', companyId] })
    void queryClient.invalidateQueries({ queryKey: ['overtimeEntries', companyId] })
    void queryClient.invalidateQueries({ queryKey: ['leaveBalances', companyId] })
  }
}

export function useApproveLeaveRequest(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useTimeoffInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      requestId,
      note,
      idempotencyKey,
    }: {
      requestId: string
      note?: string
      idempotencyKey: string
    }) =>
      apiFetch(`/api/v1/leave-requests/${requestId}/approve`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { note },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => invalidate(),
  })
}

export function useRejectLeaveRequest(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useTimeoffInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      requestId,
      note,
      idempotencyKey,
    }: {
      requestId: string
      note: string
      idempotencyKey: string
    }) =>
      apiFetch(`/api/v1/leave-requests/${requestId}/reject`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { note },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => invalidate(),
  })
}

export function useOvertimeEntries(
  params: TenantParams & { status?: string; page?: number; size?: number; enabled: boolean },
) {
  const { companyId, actor, status, page, size, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['overtimeEntries', companyId, status ?? 'all', page ?? 0, size ?? 25],
    queryFn: () =>
      apiFetch<PageResponse<OvertimeEntrySummary>>('/api/v1/overtime-entries', {
        tenant: { companyId, actor },
        query: { status, page: page?.toString(), size: size?.toString() },
      }),
  })
}

export function useApproveOvertimeEntry(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useTimeoffInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      entryId,
      note,
      idempotencyKey,
    }: {
      entryId: string
      note?: string
      idempotencyKey: string
    }) =>
      apiFetch(`/api/v1/overtime-entries/${entryId}/approve`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { note },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => invalidate(),
  })
}

export function useRejectOvertimeEntry(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useTimeoffInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      entryId,
      note,
      idempotencyKey,
    }: {
      entryId: string
      note: string
      idempotencyKey: string
    }) =>
      apiFetch(`/api/v1/overtime-entries/${entryId}/reject`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { note },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: () => invalidate(),
  })
}

/** GET /api/v1/work-calendar — seeds the tenant's default (5, 21) row on first read (server-side). */
export function useWorkCalendar(params: TenantParams & { enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['workCalendar', companyId],
    queryFn: () => apiFetch<WorkCalendarRow>('/api/v1/work-calendar', { tenant: { companyId, actor } }),
  })
}

export function useUpsertWorkCalendar(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: WorkCalendarRow) =>
      apiFetch<WorkCalendarRow>('/api/v1/work-calendar', {
        method: 'PUT',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['workCalendar', companyId] })
    },
  })
}

export function useLeaveBalances(
  params: TenantParams & { year: number; page?: number; size?: number; enabled: boolean },
) {
  const { companyId, actor, year, page, size, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['leaveBalances', companyId, year, page ?? 0, size ?? 50],
    queryFn: () =>
      apiFetch<PageResponse<LeaveBalanceRow>>('/api/v1/leave-balances', {
        tenant: { companyId, actor },
        query: { year: year.toString(), page: page?.toString(), size: size?.toString() },
      }),
  })
}

export function useAdjustLeaveBalance(params: TenantParams) {
  const { companyId, actor } = params
  const invalidate = useTimeoffInvalidate(companyId)
  return useMutation({
    mutationFn: ({
      employeeId,
      year,
      adjustmentDays,
    }: {
      employeeId: string
      year: number
      adjustmentDays: number
    }) =>
      apiFetch<LeaveBalanceRow>(`/api/v1/leave-balances/${employeeId}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        query: { year: year.toString() },
        body: { adjustmentDays },
      }),
    onSuccess: () => invalidate(),
  })
}
