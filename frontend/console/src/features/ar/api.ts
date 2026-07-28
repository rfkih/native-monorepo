import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/**
 * Accounts Receivable — typed client for finance-service's customer/invoice/aging endpoints
 * (all via the gateway, owner/manager only). Money on the wire is always integer minor units +
 * an ISO-4217 currency string (rule 8) — never a float. Mirrors the shape/conventions of
 * features/close/api.ts and features/org/api.ts.
 */

// ---------------------------------------------------------------------------
// Customers
// ---------------------------------------------------------------------------

export interface Customer {
  id: string
  name: string
  email: string | null
  taxId: string | null
  active: boolean
}

/** POST /api/v1/customers body. */
export interface CreateCustomerBody {
  name: string
  email?: string
  taxId?: string
}

/** GET /api/v1/customers — every customer for the bound company. */
export function useCustomers(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['arCustomers', companyId],
    queryFn: async () => {
      const result = await apiFetch<Customer[]>('/api/v1/customers', {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/** POST /api/v1/customers — create a customer. */
export function useCreateCustomer(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCustomerBody) =>
      apiFetch<Customer>('/api/v1/customers', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['arCustomers', companyId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Invoices — list + detail
// ---------------------------------------------------------------------------

export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'VOID'

/** Mirror of finance-service InvoiceSummary (list row). */
export interface InvoiceSummary {
  id: string
  invoiceNumber: string
  customerId: string
  customerName: string
  status: InvoiceStatus
  issueDate: string | null
  dueDate: string | null
  currency: string
  totalMinor: number
  paidMinor: number
  outstandingMinor: number
}

/** GET /api/v1/invoices?status=&customerId=&period= — filterable invoice list. */
export function useInvoices(params: {
  companyId: string
  actor: string
  status?: string
  customerId?: string
  period?: string
  enabled: boolean
}) {
  const { companyId, actor, status, customerId, period, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['arInvoices', companyId, status ?? '', customerId ?? '', period ?? ''],
    queryFn: async () => {
      const result = await apiFetch<InvoiceSummary[]>('/api/v1/invoices', {
        tenant: { companyId, actor },
        query: { status, customerId, period },
      })
      return result ?? []
    },
  })
}

export interface InvoiceLine {
  lineNo: number
  description: string
  quantity: number
  unitPriceMinor: number
  lineTotalMinor: number
}

export interface InvoicePayment {
  id: string
  amountMinor: number
  currency: string
  paidAt: string
  method: string | null
}

/** Mirror of finance-service InvoiceDetail. */
export interface InvoiceDetail {
  id: string
  invoiceNumber: string
  customerId: string
  customerName: string
  status: InvoiceStatus
  issueDate: string | null
  dueDate: string | null
  currency: string
  subtotalMinor: number
  taxMinor: number
  totalMinor: number
  paidMinor: number
  outstandingMinor: number
  usesIllustrativeRules: boolean
  lines: InvoiceLine[]
  payments: InvoicePayment[]
}

/** GET /api/v1/invoices/{id}. */
export function useInvoice(params: {
  companyId: string
  actor: string
  id: string
  enabled: boolean
}) {
  const { companyId, actor, id, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['arInvoice', companyId, id],
    queryFn: () =>
      apiFetch<InvoiceDetail>(`/api/v1/invoices/${id}`, {
        tenant: { companyId, actor },
      }),
  })
}

export interface CreateInvoiceLineBody {
  description: string
  quantity: number
  unitPriceMinor: number
}

/** POST /api/v1/invoices body. */
export interface CreateInvoiceBody {
  customerId: string
  currency: string
  taxable: boolean
  lines: CreateInvoiceLineBody[]
}

/** POST /api/v1/invoices — create a DRAFT invoice. */
export function useCreateInvoice(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateInvoiceBody) =>
      apiFetch<InvoiceDetail>('/api/v1/invoices', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['arInvoices', companyId] })
    },
  })
}

function invalidateInvoice(
  queryClient: ReturnType<typeof useQueryClient>,
  companyId: string,
  id: string,
) {
  void queryClient.invalidateQueries({ queryKey: ['arInvoice', companyId, id] })
  void queryClient.invalidateQueries({ queryKey: ['arInvoices', companyId] })
  // Partial-key match — covers every ['arAging', companyId, asOf] cached for any asOf date.
  void queryClient.invalidateQueries({ queryKey: ['arAging', companyId] })
}

/** POST /api/v1/invoices/{id}/issue body — termDays is optional. */
export interface IssueInvoiceBody {
  termDays?: number
}

/** POST /api/v1/invoices/{id}/issue — DRAFT → ISSUED. */
export function useIssueInvoice(params: { companyId: string; actor: string; id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: IssueInvoiceBody) =>
      apiFetch<InvoiceDetail>(`/api/v1/invoices/${id}/issue`, {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => invalidateInvoice(queryClient, companyId, id),
  })
}

/** POST /api/v1/invoices/{id}/void — DRAFT/ISSUED (unpaid) → VOID. */
export function useVoidInvoice(params: { companyId: string; actor: string; id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<InvoiceDetail>(`/api/v1/invoices/${id}/void`, {
        method: 'POST',
        tenant: { companyId, actor },
      }),
    onSuccess: () => invalidateInvoice(queryClient, companyId, id),
  })
}

/** POST /api/v1/invoices/{id}/payments body. */
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
 * POST /api/v1/invoices/{id}/payments — record a payment against the invoice. The backend
 * REQUIRES an `Idempotency-Key` header (a keyless request is a 400) and de-dupes retries of that
 * key scoped to the invoice.
 */
export function useRecordPayment(params: { companyId: string; actor: string; id: string }) {
  const { companyId, actor, id } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: RecordPaymentVariables) =>
      apiFetch<InvoiceDetail>(`/api/v1/invoices/${id}/payments`, {
        method: 'POST',
        tenant: { companyId, actor },
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => invalidateInvoice(queryClient, companyId, id),
  })
}

// ---------------------------------------------------------------------------
// AR aging
// ---------------------------------------------------------------------------

export interface AgingRow {
  customerId: string
  customerName: string
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

/** Mirror of finance-service AR aging response (GET /api/v1/ar/aging). */
export interface ArAgingResponse {
  asOf: string
  currency: string
  rows: AgingRow[]
  totals: AgingTotals
}

/** GET /api/v1/ar/aging?asOf= — asOf is optional (server defaults to today). */
export function useArAging(params: {
  companyId: string
  actor: string
  asOf?: string
  enabled: boolean
}) {
  const { companyId, actor, asOf, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['arAging', companyId, asOf ?? ''],
    queryFn: () =>
      apiFetch<ArAgingResponse>('/api/v1/ar/aging', {
        tenant: { companyId, actor },
        query: { asOf },
      }),
  })
}
