import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/**
 * Bank & Reconciliation — typed client for finance-service's bank-account/statement-line/
 * reconciliation endpoints (all via the gateway, owner/manager only). Money on the wire is always
 * integer minor units + an ISO-4217 currency string (rule 8) — never a float. Unlike AR/AP,
 * `amountMinor` on a statement line is SIGNED: positive = deposit, negative = withdrawal; the sign
 * is preserved end to end, never inferred from a separate flag. Mirrors the shape/conventions of
 * features/ar/api.ts and features/ap/api.ts (customer/vendor → bank account, invoice/bill →
 * statement line).
 */

// ---------------------------------------------------------------------------
// Bank accounts
// ---------------------------------------------------------------------------

export interface BankAccount {
  id: string
  name: string
  bankName: string | null
  accountNumber: string | null
  currency: string
  active: boolean
}

/** POST /api/v1/bank-accounts body. */
export interface CreateBankAccountBody {
  name: string
  bankName?: string
  accountNumber?: string
  currency: string
}

/** PATCH /api/v1/bank-accounts/{id} body. */
export interface UpdateBankAccountBody {
  name?: string
  accountNumber?: string
  active?: boolean
}

/** GET /api/v1/bank-accounts — every bank account for the bound company. */
export function useBankAccounts(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['bankAccounts', companyId],
    queryFn: async () => {
      const result = await apiFetch<BankAccount[]>('/api/v1/bank-accounts', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/bank-accounts/{id}. */
export function useBankAccount(params: {
  companyId: string
  actor: string
  id: string
  enabled: boolean
}) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['bankAccount', companyId, id],
    queryFn: () =>
      apiFetch<BankAccount>(`/api/v1/bank-accounts/${id}`, {
        tenant: { companyId, actor },
      }),
  })
}

/** POST /api/v1/bank-accounts — create a bank account. */
export function useCreateBankAccount(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateBankAccountBody) =>
      apiFetch<BankAccount>('/api/v1/bank-accounts', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['bankAccounts', companyId] })
    },
  })
}

/** PATCH /api/v1/bank-accounts/{id} — edit name/account number/active. */
export function useUpdateBankAccount(params: { companyId: string; actor: string; id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: UpdateBankAccountBody) =>
      apiFetch<BankAccount>(`/api/v1/bank-accounts/${id}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['bankAccounts', companyId] })
      void queryClient.invalidateQueries({ queryKey: ['bankAccount', companyId, id] })
    },
  })
}

// ---------------------------------------------------------------------------
// Statement lines
// ---------------------------------------------------------------------------

export type StatementLineStatus = 'UNRECONCILED' | 'RECONCILED'

/**
 * A line's direction IS its sign — there is no separate "type" field. Deposit (amount > 0) may
 * reconcile as CLEARING (settles a prior receipt) or INTEREST; withdrawal (amount < 0) as
 * CLEARING (settles a prior payment) or BANK_FEE. The UI must only offer the direction-valid pair
 * (see `categoriesFor` in ./format.ts) — the server also enforces this (400 on mismatch).
 */
export type ReconcileCategory = 'CLEARING' | 'BANK_FEE' | 'INTEREST'

export interface StatementLine {
  id: string
  bankAccountId: string
  lineDate: string
  /** SIGNED integer minor units: positive = deposit, negative = withdrawal. Never a float. */
  amountMinor: number
  currency: string
  description: string | null
  reference: string | null
  status: StatementLineStatus
  reconciledCategory: ReconcileCategory | null
  journalEntryId: string | null
}

/** GET /api/v1/bank-accounts/{id}/statement-lines?status=. */
export function useStatementLines(params: {
  companyId: string
  actor: string
  bankAccountId: string
  status?: StatementLineStatus
  enabled: boolean
}) {
  const { companyId, actor, bankAccountId, status, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['bankStatementLines', companyId, bankAccountId, status ?? ''],
    queryFn: async () => {
      const result = await apiFetch<StatementLine[]>(
        `/api/v1/bank-accounts/${bankAccountId}/statement-lines`,
        {
          tenant: { companyId, actor },
          query: { status },
        },
      )
      return result ?? []
    },
  })
}

/** One row of a POST .../statement-lines import body — amountMinor is SIGNED (preserve the sign). */
export interface ImportStatementLineBody {
  lineDate: string
  amountMinor: number
  description?: string
  reference?: string
}

export interface ImportLinesBody {
  lines: ImportStatementLineBody[]
}

/**
 * Invalidates everything derived from a bank account's statement lines: every cached status
 * filter (partial key match covers `['bankStatementLines', companyId, bankAccountId, status]` for
 * any status) and the reconciliation report. Shared by import and reconcile — both mutate the
 * line set the report is built from.
 */
function invalidateStatementLines(
  queryClient: ReturnType<typeof useQueryClient>,
  companyId: string,
  bankAccountId: string,
) {
  void queryClient.invalidateQueries({
    queryKey: ['bankStatementLines', companyId, bankAccountId],
  })
  void queryClient.invalidateQueries({
    queryKey: ['bankReconciliation', companyId, bankAccountId],
  })
}

/** POST /api/v1/bank-accounts/{id}/statement-lines — import a batch of lines. */
export function useImportLines(params: { companyId: string; actor: string; bankAccountId: string }) {
  const { companyId, actor, bankAccountId } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ImportLinesBody) =>
      apiFetch<StatementLine[]>(`/api/v1/bank-accounts/${bankAccountId}/statement-lines`, {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => invalidateStatementLines(queryClient, companyId, bankAccountId),
  })
}

export interface ReconcileVariables {
  lineId: string
  category: ReconcileCategory
}

/**
 * POST /api/v1/bank/reconcile/{lineId} body {category} — a one-shot state transition
 * (UNRECONCILED → RECONCILED), NOT a payment, so unlike AR/AP payments this does NOT send an
 * Idempotency-Key: a retried reconcile of the same line is naturally rejected by the 409
 * invalid-state check below rather than needing de-dupe.
 *
 * Reconciling posts a journal entry to the GL (CLEARING clears the matching AR/AP suspense
 * posting; BANK_FEE/INTEREST records an expense/income line) — so the consolidated income
 * statement and balance sheet will reflect it on their next load. We do not reach into those
 * unrelated features' query caches here (they're period-scoped, not statement/report-scoped);
 * this comment is the flag, not a cross-feature invalidation.
 */
export function useReconcile(params: { companyId: string; actor: string; bankAccountId: string }) {
  const { companyId, actor, bankAccountId } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ lineId, category }: ReconcileVariables) =>
      apiFetch<StatementLine>(`/api/v1/bank/reconcile/${lineId}`, {
        method: 'POST',
        tenant: { companyId, actor },
        body: { category },
      }),
    onSuccess: () => invalidateStatementLines(queryClient, companyId, bankAccountId),
  })
}

// ---------------------------------------------------------------------------
// Reconciliation report
// ---------------------------------------------------------------------------

/** Mirror of finance-service's reconciliation report (GET /api/v1/bank/reconciliation). */
export interface ReconciliationReport {
  bankAccountId: string
  currency: string
  bankBalanceMinor: number
  cashClearingBalanceMinor: number
  unreconciledCount: number
  unreconciledLines: StatementLine[]
}

/** GET /api/v1/bank/reconciliation?bankAccountId=. */
export function useReconciliation(params: {
  companyId: string
  actor: string
  bankAccountId: string
  enabled: boolean
}) {
  const { companyId, actor, bankAccountId, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['bankReconciliation', companyId, bankAccountId],
    queryFn: () =>
      apiFetch<ReconciliationReport>('/api/v1/bank/reconciliation', {
        tenant: { companyId, actor },
        query: { bankAccountId },
      }),
  })
}
