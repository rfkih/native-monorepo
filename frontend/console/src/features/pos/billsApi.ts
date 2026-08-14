/**
 * Bills (guest tabs) API hooks.
 *
 * All amounts are integer minor units as per rule 8.
 * No hardcoded strings — all user-facing text is in i18n keys (rule 9).
 *
 * DTOs mirror backend bill.dto:
 *   BillSummaryResponse  — list endpoint (no line detail)
 *   BillResponse         — single/write endpoint (lines + breakdown)
 *   BillLineResponse     — one line on a bill
 *   BillLineModifierResponse — modifier snapshot on a line
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { PriceBreakdownResponse, OrderLineInput, PaymentResponse } from './api'

// ---------------------------------------------------------------------------
// Response types — mirroring backend DTOs exactly
// ---------------------------------------------------------------------------

export interface BillLineModifierResponse {
  /** Snapshot of the option's UUID */
  optionId: string
  /** Option name at append time */
  nameSnapshot: string
  /** Signed price delta in minor units */
  priceDeltaMinor: number
}

export interface BillLineResponse {
  /** Bill line id — individually addressable (Increment 3 split-by-item) */
  id: string
  menuItemId: string
  /** Item name captured at append time */
  nameSnapshot: string
  /** Base unit price in minor units */
  unitPriceMinor: number
  /** Sum of selected modifier deltas in minor units */
  modifierDeltaMinor: number
  qty: number
  /** Pre-computed line total in minor units */
  lineTotalMinor: number
  modifiers: BillLineModifierResponse[]
  /** True once this line has been included in a paid check (Increment 3 split-by-item) */
  paid: boolean
}

export interface BillResponse {
  id: string
  businessId: string
  /** Null for takeaway / no-table bills */
  tableId: string | null
  guestLabel: string
  /** OPEN | PAID | CANCELLED */
  status: string
  /** ISO-4217 currency code */
  currency: string
  /** Fixed discount applied at pay time; null when none */
  discountMinor: number | null
  /** Set only when status = PAID */
  saleId: string | null
  lines: BillLineResponse[]
  /** Non-null on single-bill GET and every write response; null on list responses */
  breakdown: PriceBreakdownResponse | null
}

export interface BillSummaryResponse {
  id: string
  businessId: string
  tableId: string | null
  guestLabel: string
  status: string
  currency: string
  discountMinor: number | null
  /** Sum of all line totals in minor units (pre-tax/SC subtotal) */
  runningTotalMinor: number
  lineCount: number
}

// ---------------------------------------------------------------------------
// Request types
// ---------------------------------------------------------------------------

export interface OpenBillInput {
  tableId?: string | null
  guestLabel: string
}

export interface AppendLinesInput {
  billId: string
  lines: OrderLineInput[]
}

export interface PayBillInput {
  billId: string
  payment?: {
    tenderType: 'CASH' | 'QRIS' | 'CARD' | 'ONLINE'
    tenderedMinor?: number
    /** Required for ONLINE — the picked sales channel's immutable UPPERCASE code (ADR 0036 Phase B). */
    channelCode?: string
  }
  discountMinor?: number
  /** Restrict this check to a subset of unpaid lines (Increment 3 split-by-item). */
  lineIds?: string[]
  /** Caller-supplied idempotency key — send a fresh UUID per check-pay attempt. */
  idempotencyKey?: string
}

/**
 * Request body for {@link usePayBillPending} — full-bill only (no `lineIds`; see
 * `features/pos/lib/billGatewayQris.ts`'s `shouldUseBillGatewayFlow` guard). `discountMinor` mirrors
 * {@link PayBillInput}'s field verbatim; BillPaymentModal does not currently source a discount for
 * this leg (same as the existing one-step `usePayBill` call it mirrors), but the field is wired
 * through for parity with the backend contract.
 */
export interface PayBillPendingInput {
  billId: string
  payment: { tenderType: 'QRIS' }
  discountMinor?: number
  /**
   * The SAME key BillDetail mints per pay-initiation (`freshIdempotencyKey`), reused across retries
   * of this attempt — sent for consistency with every other bill/order write on this contract, but
   * INERT server-side for `pay-pending` specifically: the backend ignores it and self-heals by
   * minting its own key (coordination note, 2026-08-14). Keep sending it (harmless); don't treat it
   * as meaningful retry-dedup for THIS endpoint the way it is for `usePayBill`/`useCheckout`.
   */
  idempotencyKey?: string
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

function billsKey(session: CompanySession) {
  return ['bills', session.companyId, session.businessId]
}

function billKey(session: CompanySession, billId: string) {
  return ['bill', session.companyId, billId]
}

// ---------------------------------------------------------------------------
// List open bills — GET /api/v1/bills?businessId=...&status=OPEN
// ---------------------------------------------------------------------------

export function useBills(session: CompanySession) {
  return useQuery({
    queryKey: billsKey(session),
    queryFn: () =>
      apiFetch<BillSummaryResponse[]>('/api/v1/bills', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId, status: 'OPEN' },
      }),
    staleTime: 10_000,
  })
}

// ---------------------------------------------------------------------------
// Get one bill (full detail) — GET /api/v1/bills/{id}
// ---------------------------------------------------------------------------

export function useBill(session: CompanySession, billId: string | null) {
  return useQuery({
    queryKey: billKey(session, billId ?? ''),
    enabled: billId != null,
    queryFn: () =>
      apiFetch<BillResponse>(`/api/v1/bills/${billId}`, {
        tenant: tenantOf(session),
      }),
  })
}

// ---------------------------------------------------------------------------
// Open a new bill — POST /api/v1/bills → 201 BillResponse
// ---------------------------------------------------------------------------

export function useOpenBill(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ tableId, guestLabel }: OpenBillInput) =>
      apiFetch<BillResponse>('/api/v1/bills', {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          tableId: tableId ?? null,
          guestLabel,
        },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: billsKey(session) })
    },
  })
}

// ---------------------------------------------------------------------------
// Append lines to a bill — POST /api/v1/bills/{id}/lines → 200 BillResponse
// ---------------------------------------------------------------------------

export function useAppendLines(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ billId, lines }: AppendLinesInput) =>
      apiFetch<BillResponse>(`/api/v1/bills/${billId}/lines`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: { lines },
      }),
    onSuccess: (res, { billId }) => {
      if (res) {
        qc.setQueryData(billKey(session, billId), res)
      }
      void qc.invalidateQueries({ queryKey: billsKey(session) })
    },
  })
}

// ---------------------------------------------------------------------------
// Remove a line — DELETE /api/v1/bills/{id}/lines/{lineId} → 204
// ---------------------------------------------------------------------------

export function useRemoveLine(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ billId, lineId }: { billId: string; lineId: string }) =>
      apiFetch<void>(`/api/v1/bills/${billId}/lines/${lineId}`, {
        method: 'DELETE',
        tenant: tenantOf(session),
      }),
    onSuccess: (_res, { billId }) => {
      void qc.invalidateQueries({ queryKey: billKey(session, billId) })
      void qc.invalidateQueries({ queryKey: billsKey(session) })
    },
  })
}

// ---------------------------------------------------------------------------
// Pay a bill — POST /api/v1/bills/{id}/pay → 200 BillResponse
// ---------------------------------------------------------------------------

export function usePayBill(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ billId, payment, discountMinor, lineIds, idempotencyKey }: PayBillInput) =>
      apiFetch<BillResponse>(`/api/v1/bills/${billId}/pay`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          payment: payment ?? null,
          discountMinor: discountMinor != null && discountMinor > 0 ? discountMinor : null,
          lineIds: lineIds && lineIds.length > 0 ? lineIds : null,
          idempotencyKey: idempotencyKey ?? null,
        },
      }),
    onSuccess: (_res, { billId }) => {
      void qc.invalidateQueries({ queryKey: billKey(session, billId) })
      void qc.invalidateQueries({ queryKey: billsKey(session) })
      void qc.invalidateQueries({ queryKey: ['pnl'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Pay a bill — GATEWAY QRIS two-step leg (ADR 0045 extension to bills, full-bill only):
// POST /api/v1/bills/{id}/pay-pending → 201 PaymentResponse (creates a PENDING payment WITHOUT
// settling the bill) + POST /api/v1/payments/{id}/abandon (releases the reservation on a clean
// cancel/close). Mirrors the order checkout's two-step digital leg (features/pos/api.ts's
// useCheckout + useCapturePayment) — capture/receipt for the resulting payment reuse THOSE hooks
// verbatim (BillPaymentModal imports useCapturePayment/useReceipt from ./api), nothing here
// duplicates them.
// ---------------------------------------------------------------------------

/**
 * POST /api/v1/bills/{id}/pay-pending — creates a PENDING payment for the bill's current unpaid
 * total (the full, un-split bill) without settling it. The response is the SAME `PaymentResponse`
 * shape `useCapturePayment`/`useReceipt` (features/pos/api.ts) already return — reused verbatim
 * rather than redeclared, so `useGatewayQris` and `GatewayQrisPendingView` drive off one consistent
 * type across the order and bill flows. Only `paymentId`/`amountMinor`/`currency`/`status` are ever
 * read off a bill-originated payment here — `orderId` is null (a bill is not an order; `billId` is
 * its bill-side counterpart, unread here too).
 * No cache invalidation on success: creating the PENDING payment doesn't change anything the bill
 * read shows yet (unlike capture, which the caller invalidates on the CAPTURED transition).
 */
export function usePayBillPending(session: CompanySession) {
  return useMutation({
    mutationFn: ({ billId, payment, discountMinor, idempotencyKey }: PayBillPendingInput) =>
      apiFetch<PaymentResponse>(`/api/v1/bills/${billId}/pay-pending`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          payment,
          discountMinor: discountMinor != null && discountMinor > 0 ? discountMinor : null,
          idempotencyKey: idempotencyKey ?? null,
        },
      }),
  })
}

/**
 * POST /api/v1/payments/{id}/abandon — releases a bill's line reservation after a PENDING GATEWAY
 * QRIS payment (from {@link usePayBillPending}) is cleanly cancelled, or the payment modal closes
 * mid-pending — BillPaymentModal's `handleGatewayCancel`. No order-side caller exists yet, so this
 * lives here rather than in features/pos/api.ts; the plain shape otherwise matches
 * `useCapturePayment`/`useReceipt` exactly (same payment-id path param, same response type).
 */
export function useAbandonPayment(session: CompanySession) {
  return useMutation({
    mutationFn: (paymentId: string) =>
      apiFetch<PaymentResponse>(`/api/v1/payments/${paymentId}/abandon`, {
        method: 'POST',
        tenant: tenantOf(session),
      }),
  })
}

// ---------------------------------------------------------------------------
// Cancel a bill — POST /api/v1/bills/{id}/cancel → 204
// ---------------------------------------------------------------------------

export function useCancelBill(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (billId: string) =>
      apiFetch<void>(`/api/v1/bills/${billId}/cancel`, {
        method: 'POST',
        tenant: tenantOf(session),
      }),
    onSuccess: (_res, billId) => {
      void qc.invalidateQueries({ queryKey: billKey(session, billId) })
      void qc.invalidateQueries({ queryKey: billsKey(session) })
    },
  })
}
