/**
 * companyExpenseApi.ts — the "Catat pengeluaran" company-expense input (ADR 0072 P3):
 * `/api/v1/company-expenses/**` on finance-service, gated FINANCE_ROLES (owner/accountant) at the
 * gateway. Mirrors features/ap/api.ts's PERSONAL-bearer shadow (every DASHBOARD-style back-office
 * call here uses the elevation token on a device terminal, byte-identical to the single login
 * token for a normal `user` login).
 *
 * One submit records EITHER a GENERAL category expense (`glHint` + `amountMinor`, no lines) OR an
 * INVENTORY ingredient purchase (`lines`, `amountMinor` OMITTED — the server sums the lines'
 * `valueMinor` as the authoritative amount; the console's own total is display-only). See
 * `NewCompanyExpense.tsx` for the form and
 * docs/adr/0072-purchase-linked-inventory-and-periodic-cogs-routing.md for the accounting this
 * feeds (periodic HPP routing, the finance→restaurant `InventoryPurchaseRecorded` seam).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'

/** ADR 0049 P3b — `/api/v1/company-expenses/**` is FINANCE_ROLES-gated, so every call below uses
 *  the PERSONAL bearer (see features/ap/api.ts's identical shadow for the doc). */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

interface TenantParams {
  companyId: string
  actor: string
}

export type CompanyExpenseKind = 'GENERAL' | 'INVENTORY'
export type CompanyExpenseStatus = 'POSTED' | 'VOID'

/** One ingredient line of an INVENTORY expense — the REQUEST shape (mirrors finance-service's
 *  `RecordCompanyExpenseRequest.LineRequest`). `qtyBase` is the ingredient's BASE unit — convert a
 *  display-unit input (kg/liter) via `features/inventory/lib/units.ts` before sending, mirroring
 *  the Terima ("receive") dialog's own conversion. `valueMinor` is the amount paid for THIS line,
 *  minor units of the company base currency. Finance stores `ingredientId`/`ingredientName` as
 *  opaque snapshots (ADR 0072 — it cannot validate them against restaurant-service, rule 1); the
 *  console picker is what keeps garbage out. */
export interface CompanyExpenseLineInput {
  ingredientId: string
  ingredientName: string
  qtyBase: number
  valueMinor: number
}

/** One ingredient line as returned by a detail read (mirrors `CompanyExpenseResponse.LineResponse`
 *  — `id` is the LINE's own id, `lineNo` its 1-based position). Empty on the list read. */
export interface CompanyExpenseLine {
  id: string
  lineNo: number
  ingredientId: string
  ingredientName: string
  qtyBase: number
  valueMinor: number
}

/** A recorded company expense (mirrors `CompanyExpenseResponse`) — `lines` is always `[]` on the
 *  LIST read (`useCompanyExpenses`), filled on the detail read (`useCompanyExpense`). */
export interface CompanyExpense {
  id: string
  expenseNo: string
  kind: CompanyExpenseKind
  businessId: string
  glHint: string
  description: string
  amountMinor: number
  currency: string
  occurredAt: string
  status: CompanyExpenseStatus
  lines: CompanyExpenseLine[]
}

function invalidateCompanyExpenses(
  queryClient: ReturnType<typeof useQueryClient>,
  companyId: string,
  id?: string,
) {
  void queryClient.invalidateQueries({ queryKey: ['companyExpenses', companyId] })
  if (id) void queryClient.invalidateQueries({ queryKey: ['companyExpense', companyId, id] })
}

/** POST /api/v1/company-expenses body — `kind` decides the shape: GENERAL sends `glHint` +
 *  `amountMinor` (no `lines`); INVENTORY sends `lines` and OMITS `amountMinor` (the server computes
 *  it as the lines' sum). `currency` is always the company base currency (rule: no currency toggle
 *  in the dashboard). `occurredAt` is optional — omitted, the server defaults to now. */
export interface RecordCompanyExpenseBody {
  kind: CompanyExpenseKind
  businessId: string
  glHint?: string
  description: string
  amountMinor?: number
  currency: string
  occurredAt?: string
  lines?: CompanyExpenseLineInput[]
}

/**
 * Mutation variables for {@link useRecordCompanyExpense}. `idempotencyKey` MUST be minted ONCE per
 * submit ATTEMPT-SET (`crypto.randomUUID()` in a `useRef` in the calling component, NOT inside
 * `mutationFn`, which re-runs on every TanStack Query retry and would mint a fresh key per retry) —
 * mirrors features/inventory/ingredientApi.ts's `useAddIngredientStock`/`AddIngredientStockInput`
 * idiom exactly, so a manual retry after a lost response replays instead of double-recording the
 * GL posting (and, for INVENTORY, the stock receive).
 */
export interface RecordCompanyExpenseVariables extends RecordCompanyExpenseBody {
  idempotencyKey: string
}

/** POST /api/v1/company-expenses — 201 `{ id }`. */
export function useRecordCompanyExpense(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: RecordCompanyExpenseVariables) =>
      apiFetch<{ id: string }>('/api/v1/company-expenses', {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => invalidateCompanyExpenses(queryClient, companyId),
  })
}

/** GET /api/v1/company-expenses?limit= — recent expenses, newest first, no lines. */
export function useCompanyExpenses(params: TenantParams & { limit?: number; enabled: boolean }) {
  const { companyId, actor, limit, enabled } = params
  const boundedLimit = limit ?? 50
  return useQuery({
    enabled,
    queryKey: ['companyExpenses', companyId, boundedLimit],
    queryFn: async () => {
      const result = await apiFetch<CompanyExpense[]>('/api/v1/company-expenses', {
        tenant: { companyId, actor },
        query: { limit: String(boundedLimit) },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/company-expenses/{id} — the summary plus its ingredient lines (empty for GENERAL). */
export function useCompanyExpense(params: TenantParams & { id: string | null; enabled: boolean }) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled: enabled && !!id,
    queryKey: ['companyExpense', companyId, id],
    queryFn: () =>
      apiFetch<CompanyExpense>(`/api/v1/company-expenses/${id}`, {
        tenant: { companyId, actor },
      }),
  })
}

export interface VoidCompanyExpenseVariables {
  id: string
}

/** POST /api/v1/company-expenses/{id}/void — money-side contra only (ADR 0072 §4: stock is NOT
 *  auto-reversed — the UI directs the operator to "Atur jumlah"/stock opname); 409 when already
 *  void. No Idempotency-Key on this endpoint (mirrors the server contract — see
 *  `CompanyExpenseController#voidExpense`). */
export function useVoidCompanyExpense(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id }: VoidCompanyExpenseVariables) =>
      apiFetch<{ id: string }>(`/api/v1/company-expenses/${id}/void`, {
        method: 'POST',
        tenant: { companyId, actor },
      }),
    onSuccess: (_, { id }) => invalidateCompanyExpenses(queryClient, companyId, id),
  })
}
