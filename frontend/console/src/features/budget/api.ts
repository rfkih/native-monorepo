import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/**
 * Budgets — typed client for finance-service's /api/v1/budgets endpoints (via the gateway,
 * owner/manager only). A budget is a per-month set of planned amounts per account; the actuals
 * report compares each line's plan against the period's GL actual. Budgets are PLANNING DATA — they
 * never post to the GL. Money on the wire is integer minor units + an ISO-4217 currency (rule 8).
 */

export interface BudgetSummary {
  id: string
  name: string
  period: string
  currency: string
  lineCount: number
  totalPlannedMinor: number
}

export interface BudgetLine {
  accountCode: string
  accountName: string
  accountType: string
  amountMinor: number
}

export interface BudgetDetail {
  id: string
  name: string
  period: string
  currency: string
  lines: BudgetLine[]
}

export interface BudgetActualLine {
  accountCode: string
  accountName: string
  accountType: string
  plannedMinor: number
  actualMinor: number
  varianceMinor: number
}

export interface BudgetActuals {
  budgetId: string
  name: string
  period: string
  currency: string
  lines: BudgetActualLine[]
  totalPlannedMinor: number
  totalActualMinor: number
  totalVarianceMinor: number
  usesIllustrativeRules: boolean
}

export interface Account {
  accountCode: string
  name: string
  accountType: string
}

export interface CreateBudgetBody {
  name: string
  period: string
  currency: string
  lines: { accountCode: string; amountMinor: number }[]
}

/** GET /api/v1/budgets — the budget list for the bound company. */
export function useBudgets(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['budgets', companyId],
    queryFn: async () => {
      const result = await apiFetch<BudgetSummary[]>('/api/v1/budgets', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/budgets/{id} — a budget's detail (header + planned lines). */
export function useBudget(params: {
  companyId: string
  actor: string
  id: string
  enabled: boolean
}) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['budget', companyId, id],
    queryFn: () =>
      apiFetch<BudgetDetail>(`/api/v1/budgets/${id}`, { tenant: { companyId, actor } }),
  })
}

/** GET /api/v1/budgets/{id}/actuals — the budget-vs-actual variance report. */
export function useBudgetActuals(params: {
  companyId: string
  actor: string
  id: string
  enabled: boolean
}) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['budgetActuals', companyId, id],
    queryFn: () =>
      apiFetch<BudgetActuals>(`/api/v1/budgets/${id}/actuals`, { tenant: { companyId, actor } }),
  })
}

/** GET /api/v1/budgets/accounts — the chart-of-account picker options. */
export function useAccounts(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['budgetAccounts', companyId],
    queryFn: async () => {
      const result = await apiFetch<Account[]>('/api/v1/budgets/accounts', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** POST /api/v1/budgets — create a budget with its planned lines. */
export function useCreateBudget(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateBudgetBody) =>
      apiFetch<BudgetDetail>('/api/v1/budgets', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['budgets', companyId] })
    },
  })
}

/** DELETE /api/v1/budgets/{id} — delete a budget and its lines. */
export function useDeleteBudget(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<void>(`/api/v1/budgets/${id}`, {
        method: 'DELETE',
        tenant: { companyId, actor },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['budgets', companyId] })
    },
  })
}
