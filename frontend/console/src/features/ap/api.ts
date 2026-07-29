import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/**
 * Accounts Payable — typed client for finance-service's vendor/bill/aging endpoints
 * (all via the gateway, owner/manager only). Money on the wire is always integer minor units +
 * an ISO-4217 currency string (rule 8) — never a float. Mirrors the shape/conventions of
 * features/ar/api.ts (customer→vendor, invoice→bill, ISSUED→POSTED).
 */

// ---------------------------------------------------------------------------
// Vendors
// ---------------------------------------------------------------------------

export interface Vendor {
  id: string
  name: string
  email: string | null
  taxId: string | null
  active: boolean
}

/** POST /api/v1/vendors body. */
export interface CreateVendorBody {
  name: string
  email?: string
  taxId?: string
}

/** GET /api/v1/vendors — every vendor for the bound company. */
export function useVendors(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['apVendors', companyId],
    queryFn: async () => {
      const result = await apiFetch<Vendor[]>('/api/v1/vendors', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** POST /api/v1/vendors — create a vendor. */
export function useCreateVendor(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateVendorBody) =>
      apiFetch<Vendor>('/api/v1/vendors', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['apVendors', companyId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Bills — list + detail
// ---------------------------------------------------------------------------

export type BillStatus = 'DRAFT' | 'POSTED' | 'PARTIALLY_PAID' | 'PAID' | 'VOID'

/** Mirror of finance-service BillSummary (list row). */
export interface BillSummary {
  id: string
  billNumber: string
  vendorId: string
  vendorName: string
  status: BillStatus
  billDate: string | null
  dueDate: string | null
  currency: string
  totalMinor: number
  paidMinor: number
  outstandingMinor: number
}

/** GET /api/v1/ap/bills?status=&vendorId=&period= — filterable bill list. */
export function useBills(params: {
  companyId: string
  actor: string
  status?: string
  vendorId?: string
  period?: string
  enabled: boolean
}) {
  const { companyId, actor, status, vendorId, period, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['apBills', companyId, status ?? '', vendorId ?? '', period ?? ''],
    queryFn: async () => {
      const result = await apiFetch<BillSummary[]>('/api/v1/ap/bills', {
        tenant: { companyId, actor },
        query: { status, vendorId, period },
      })
      return result ?? []
    },
  })
}

export interface BillLine {
  lineNo: number
  description: string
  quantity: number
  unitPriceMinor: number
  lineTotalMinor: number
}

export interface BillPayment {
  id: string
  amountMinor: number
  currency: string
  paidAt: string
  method: string | null
}

/** Mirror of finance-service BillDetail. */
export interface BillDetail {
  id: string
  billNumber: string
  vendorId: string
  vendorName: string
  status: BillStatus
  billDate: string | null
  dueDate: string | null
  currency: string
  subtotalMinor: number
  taxMinor: number
  totalMinor: number
  paidMinor: number
  outstandingMinor: number
  usesIllustrativeRules: boolean
  lines: BillLine[]
  payments: BillPayment[]
}

/** GET /api/v1/ap/bills/{id}. */
export function useBill(params: {
  companyId: string
  actor: string
  id: string
  enabled: boolean
}) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['apBill', companyId, id],
    queryFn: () =>
      apiFetch<BillDetail>(`/api/v1/ap/bills/${id}`, {
        tenant: { companyId, actor },
      }),
  })
}

export interface CreateBillLineBody {
  description: string
  quantity: number
  unitPriceMinor: number
}

/** POST /api/v1/ap/bills body. */
export interface CreateBillBody {
  vendorId: string
  currency: string
  taxable: boolean
  lines: CreateBillLineBody[]
}

/** POST /api/v1/ap/bills — create a DRAFT bill. */
export function useCreateBill(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateBillBody) =>
      apiFetch<BillDetail>('/api/v1/ap/bills', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['apBills', companyId] })
    },
  })
}

function invalidateBill(
  queryClient: ReturnType<typeof useQueryClient>,
  companyId: string,
  id: string,
) {
  void queryClient.invalidateQueries({ queryKey: ['apBill', companyId, id] })
  void queryClient.invalidateQueries({ queryKey: ['apBills', companyId] })
  // Partial-key match — covers every ['apAging', companyId, asOf] cached for any asOf date.
  void queryClient.invalidateQueries({ queryKey: ['apAging', companyId] })
}

/** POST /api/v1/ap/bills/{id}/post body — termDays is optional. */
export interface PostBillBody {
  termDays?: number
}

/** POST /api/v1/ap/bills/{id}/post — DRAFT → POSTED. */
export function usePostBill(params: { companyId: string; actor: string; id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: PostBillBody) =>
      apiFetch<BillDetail>(`/api/v1/ap/bills/${id}/post`, {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => invalidateBill(queryClient, companyId, id),
  })
}

/** POST /api/v1/ap/bills/{id}/void — DRAFT/POSTED (unpaid) → VOID. */
export function useVoidBill(params: { companyId: string; actor: string; id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<BillDetail>(`/api/v1/ap/bills/${id}/void`, {
        method: 'POST',
        tenant: { companyId, actor },
      }),
    onSuccess: () => invalidateBill(queryClient, companyId, id),
  })
}

/** POST /api/v1/ap/bills/{id}/payments body. */
export interface RecordPaymentBody {
  amountMinor: number
  method?: string
}

/**
 * Mutation variables for {@link useRecordPayment}. `idempotencyKey` must be generated ONCE per
 * user submit (e.g. `crypto.randomUUID()` in the calling component's submit handler) and passed
 * in here — NOT generated inside `mutationFn`, which re-runs on every TanStack Query retry and
 * would mint a fresh key per retry, defeating the backend's retry de-dupe. Because `mutate()`
 * variables are captured once and reused verbatim across a mutation's automatic retries, a single
 * key here is stable across retries of the same attempt but fresh on the next submit.
 */
export interface RecordPaymentVariables extends RecordPaymentBody {
  idempotencyKey: string
}

/**
 * POST /api/v1/ap/bills/{id}/payments — record a payment against the bill. The backend REQUIRES an
 * `Idempotency-Key` header (a keyless request is a 400) and de-dupes retries of that key scoped
 * to the bill.
 */
export function useRecordPayment(params: { companyId: string; actor: string; id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: RecordPaymentVariables) =>
      apiFetch<BillDetail>(`/api/v1/ap/bills/${id}/payments`, {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => invalidateBill(queryClient, companyId, id),
  })
}

// ---------------------------------------------------------------------------
// AP aging
// ---------------------------------------------------------------------------

export interface AgingRow {
  vendorId: string
  vendorName: string
  currentMinor: number
  overdue1To30Minor: number
  overdue31To60Minor: number
  overdue61To90Minor: number
  overdue90PlusMinor: number
  outstandingMinor: number
}

export interface AgingTotals {
  currentMinor: number
  overdue1To30Minor: number
  overdue31To60Minor: number
  overdue61To90Minor: number
  overdue90PlusMinor: number
  outstandingMinor: number
}

/** Mirror of finance-service AP aging response (GET /api/v1/ap/aging). */
export interface ApAgingResponse {
  asOf: string
  currency: string
  rows: AgingRow[]
  totals: AgingTotals
}

/** GET /api/v1/ap/aging?asOf= — asOf is optional (server defaults to today). */
export function useApAging(params: {
  companyId: string
  actor: string
  asOf?: string
  enabled: boolean
}) {
  const { companyId, actor, asOf, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['apAging', companyId, asOf ?? ''],
    queryFn: () =>
      apiFetch<ApAgingResponse>('/api/v1/ap/aging', {
        tenant: { companyId, actor },
        query: { asOf },
      }),
  })
}
