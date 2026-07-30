import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

// ---------------------------------------------------------------------------
// Table types
// ---------------------------------------------------------------------------

export interface TableResponse {
  tableId: string
  businessId: string
  label: string
  capacity: number
  area: string | null
  active: boolean
  occupied: boolean
}

// ---------------------------------------------------------------------------
// Parked order summary (GET /api/v1/orders?status=PARKED)
// ---------------------------------------------------------------------------

export interface ParkedOrderSummary {
  orderId: string
  businessId: string
  totalMinor: number
  currency: string
  /** null for TAKEAWAY / DELIVERY */
  tableLabel: string | null
  lineCount: number
  occurredAt: string
  orderType: string
}

export interface ModifierOptionResponse {
  id: string
  groupId: string
  businessId: string
  name: string
  /** Signed price delta in minor units — 0 means no extra charge. */
  priceDeltaMinor: number
  available: boolean
  displayOrder: number
}

export interface ModifierGroupResponse {
  id: string
  menuItemId: string
  businessId: string
  name: string
  /** "SINGLE" (radio) or "MULTI" (checkbox). */
  selectionType: 'SINGLE' | 'MULTI'
  required: boolean
  minSelect: number
  maxSelect: number
  displayOrder: number
  options: ModifierOptionResponse[]
}

export interface CategoryResponse {
  id: string
  businessId: string
  name: string
  displayOrder: number
  active: boolean
}

export interface MenuItem {
  id: string
  businessId: string
  name: string
  category: string
  /** UUID of the linked MenuCategory — null for legacy items not yet assigned. */
  categoryId: string | null
  priceMinor: number
  currency: string
  active: boolean
  /** 86 flag: false = sold out; cashier read may omit the item or set this to false. */
  available: boolean
  /**
   * Stock quantity: null = infinite/untracked (manual sold-out toggle only);
   * a number ≥ 0 = tracked (auto-deducts on sale; 0 means sold out).
   */
  stockQuantity: number | null
  /** Populated on the cashier read path (GET /api/v1/menu). */
  modifierGroups: ModifierGroupResponse[]
  /**
   * Optional item photo stored as a data URL (base64-encoded JPEG) or an
   * external URL.  Null when no photo has been attached.
   */
  imageUrl: string | null
}

export interface OrderLineModifierResponse {
  optionId: string
  nameSnapshot: string
  priceDeltaMinor: number
}

export interface OrderLineInput {
  menuItemId: string
  qty: number
  /** Modifier option UUIDs selected by the customer; empty array when no modifiers chosen. */
  selectedOptionIds: string[]
}

/**
 * Mirrors backend order.dto.AppliedPromotionResponse (Phase 3, ADR 0026) — one deduction the
 * promotions engine applied, echoed ONLY on the quote response. `lineRef` is always null on a quote
 * (a quote persists nothing — see the Java javadoc).
 */
export interface AppliedPromotionResponse {
  name: string
  type: string | null
  amountMinor: number
  lineRef: string | null
}

/**
 * Mirrors backend PriceBreakdownResponse. All amounts are integer minor units.
 * When usesIllustrativeRules is true the tax / service-charge lines are placeholder
 * amounts and the UI must badge them as estimated.
 *
 * Phase 3 (ADR 0026): appliedPromotions/couponStatus are populated ONLY on the quote response — the
 * checkout/pay-parked responses keep the pre-Phase-3 shape (discountMinor already carries the
 * collapsed total; the per-rule detail is durably persisted server-side as applied_promotion rows,
 * not echoed here).
 */
export interface PriceBreakdownResponse {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  grandTotalMinor: number
  currency: string
  usesIllustrativeRules: boolean
  /** Empty on checkout/pay-parked responses — see class doc. */
  appliedPromotions: AppliedPromotionResponse[]
  /** 'APPLIED' | 'INVALID' | 'EXHAUSTED' | null — null when no coupon code was supplied. */
  couponStatus: 'APPLIED' | 'INVALID' | 'EXHAUSTED' | null
}

/** Matches backend PaymentResponse record (ADR 0006). All money is integer minor units. */
export interface PaymentResponse {
  paymentId: string
  orderId: string
  /** String name of TenderType enum: CASH | QRIS | CARD */
  tenderType: string
  /** String name of Payment.Status enum: PENDING | CAPTURED | VOIDED | REFUNDED | PARTIALLY_REFUNDED | ABANDONED | FAILED */
  status: string
  amountMinor: number
  currency: string
  /** Cash only — null for digital tenders */
  tenderedMinor: number | null
  /** Cash only — null for digital tenders */
  changeMinor: number | null
  /** True for QRIS/CARD until a real provider adapter lands (ADR 0006) */
  providerPending: boolean
  /** null until the payment is CAPTURED */
  saleId: string | null
}

export interface OrderResponse {
  orderId: string
  businessId: string
  totalMinor: number
  currency: string
  saleId: string | null
  lines: {
    menuItemId: string
    name: string
    unitPriceMinor: number
    qty: number
    lineTotalMinor: number
    /** Phase 3: modifier snapshots chosen for this line. */
    modifiers: OrderLineModifierResponse[]
  }[]
  /** Present when the order was paid in the same checkout call; null otherwise. */
  payment: PaymentResponse | null
  /** Phase 2 price breakdown — present on every newly created order. */
  breakdown: PriceBreakdownResponse | null
  /** Phase 4: PENDING | PARKED | COMPLETED | AWAITING_PAYMENT */
  status: string | null
  /** Phase 4: DINE_IN | TAKEAWAY | DELIVERY */
  orderType: string | null
  /** Phase 4: table UUID (DINE_IN only) */
  tableId: string | null
}

/** Payment block sent on checkout — tenderType must be CASH | QRIS | CARD. */
export interface CheckoutPaymentInput {
  tenderType: 'CASH' | 'QRIS' | 'CARD'
  /** Required for CASH: the physical amount handed over in minor units. Ignored for digital. */
  tenderedMinor?: number
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

// ---------------------------------------------------------------------------
// Quote hook — live price breakdown, debounced, no side-effects
// ---------------------------------------------------------------------------

/**
 * Fetches a live price breakdown from POST /api/v1/orders/quote.
 * The query is keyed by the debounced lines + discountMinor so it re-runs on every cart or
 * discount change (after 400 ms of inactivity to avoid flooding the API).
 * keepPreviousData keeps the last breakdown visible while the next one loads.
 * An empty cart short-circuits to null without any network call.
 *
 * The debounce is implemented via useState so the query key and the request body are always
 * derived from the same settled snapshot — previously a useRef approach caused the queryFn to
 * fire immediately with a stale ref body while the key already reflected the new cart, producing
 * a breakdown that did not match the current cart lines.
 *
 * Phase 3: lines now carry selectedOptionIds — forwarded verbatim to the quote endpoint so the
 * server prices modifier deltas and returns the correct subtotal/tax/total breakdown.
 *
 * Phase 3 (ADR 0026): couponCode (optional) is forwarded verbatim — the quote endpoint never throws
 * for a bad/expired/exhausted code, it reports `couponStatus` on the breakdown instead.
 */
export function useQuote(
  session: CompanySession,
  lines: OrderLineInput[],
  discountMinor: number,
  couponCode: string | null = null,
) {
  // State-based debounce: both the query key and the request body read from `debounced`,
  // so they are always identical and the server always receives the full current cart.
  const [debounced, setDebounced] = useState<{
    lines: OrderLineInput[]
    discountMinor: number
    couponCode: string | null
  }>(() => ({ lines, discountMinor, couponCode }))
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      setDebounced({ lines, discountMinor, couponCode })
    }, 400)
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [lines, discountMinor, couponCode])

  const enabled = debounced.lines.length > 0

  return useQuery({
    queryKey: [
      'quote',
      session.companyId,
      session.businessId,
      debounced.lines,
      debounced.discountMinor,
      debounced.couponCode,
    ],
    enabled,
    placeholderData: keepPreviousData,
    staleTime: 0,
    queryFn: () =>
      apiFetch<PriceBreakdownResponse>('/api/v1/orders/quote', {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          lines: debounced.lines,
          discountMinor: debounced.discountMinor > 0 ? debounced.discountMinor : null,
          couponCode: debounced.couponCode || null,
        },
      }),
  })
}

export function useMenu(session: CompanySession) {
  return useQuery({
    queryKey: ['menu', session.companyId, session.businessId],
    queryFn: () =>
      apiFetch<MenuItem[]>('/api/v1/menu', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

export function useCategories(session: CompanySession) {
  return useQuery({
    queryKey: ['menu-categories', session.companyId, session.businessId],
    queryFn: () =>
      apiFetch<CategoryResponse[]>('/api/v1/menu/categories', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

export interface CheckoutInput {
  lines: OrderLineInput[]
  payment?: CheckoutPaymentInput
  /** Optional order-level discount in minor units (>= 0). Server clamps to subtotal. */
  discountMinor?: number
  /** Phase 4: DINE_IN | TAKEAWAY | DELIVERY */
  orderType?: string
  /** Phase 4: table UUID (DINE_IN only) */
  tableId?: string | null
  /** Phase 3 (ADR 0026): optional coupon code. Unlike the quote, checkout REJECTS a bad/exhausted code. */
  couponCode?: string | null
}

export function useCheckout(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ lines, payment, discountMinor, orderType, tableId, couponCode }: CheckoutInput) =>
      apiFetch<OrderResponse>('/api/v1/orders', {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          idempotencyKey: crypto.randomUUID(),
          lines,
          payment: payment ?? null,
          discountMinor: discountMinor && discountMinor > 0 ? discountMinor : null,
          orderType: orderType ?? null,
          tableId: tableId ?? null,
          couponCode: couponCode || null,
        },
      }),
    onSuccess: (res) => {
      // A CAPTURED payment recorded a Sale → consolidated dashboard revenue changes.
      if (res?.payment?.status === 'CAPTURED') {
        void qc.invalidateQueries({ queryKey: ['pnl'] })
      }
      // Occupancy may have changed — invalidate table list.
      void qc.invalidateQueries({ queryKey: ['tables', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Park (hold) order — POST /api/v1/orders/park → PARKED OrderResponse
// ---------------------------------------------------------------------------

export interface ParkInput {
  lines: OrderLineInput[]
  orderType?: string
  tableId?: string | null
  discountMinor?: number
}

export function useParkOrder(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ lines, orderType, tableId, discountMinor }: ParkInput) =>
      apiFetch<OrderResponse>('/api/v1/orders/park', {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          idempotencyKey: crypto.randomUUID(),
          lines,
          orderType: orderType ?? null,
          tableId: tableId ?? null,
          discountMinor: discountMinor && discountMinor > 0 ? discountMinor : null,
        },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['parked-orders', session.companyId, session.businessId] })
      void qc.invalidateQueries({ queryKey: ['tables', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Parked orders list — GET /api/v1/orders?status=PARKED&businessId=...
// ---------------------------------------------------------------------------

export function useParkedOrders(session: CompanySession) {
  return useQuery({
    queryKey: ['parked-orders', session.companyId, session.businessId],
    queryFn: () =>
      apiFetch<ParkedOrderSummary[]>('/api/v1/orders', {
        tenant: tenantOf(session),
        query: { status: 'PARKED', businessId: session.businessId },
      }),
    staleTime: 10_000,
  })
}

// ---------------------------------------------------------------------------
// Get one order — GET /api/v1/orders/{id}  (resume a parked order)
// ---------------------------------------------------------------------------

export function useGetOrder(session: CompanySession, orderId: string | null) {
  return useQuery({
    queryKey: ['order', session.companyId, orderId],
    enabled: orderId != null,
    queryFn: () =>
      apiFetch<OrderResponse>(`/api/v1/orders/${orderId}`, {
        tenant: tenantOf(session),
      }),
  })
}

// ---------------------------------------------------------------------------
// Pay parked order — POST /api/v1/orders/{id}/pay
// ---------------------------------------------------------------------------

export interface PayParkedInput {
  orderId: string
  payment?: CheckoutPaymentInput
  /** Phase 3 (ADR 0026): optional coupon code, re-evaluated at pay time (a parked order does not
   * lock in promotions at park time). Rejected the same way checkout rejects a bad/exhausted code. */
  couponCode?: string | null
}

export function usePayParked(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ orderId, payment, couponCode }: PayParkedInput) =>
      apiFetch<OrderResponse>(`/api/v1/orders/${orderId}/pay`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: { payment: payment ?? null, couponCode: couponCode || null },
      }),
    onSuccess: (res) => {
      if (res?.payment?.status === 'CAPTURED') {
        void qc.invalidateQueries({ queryKey: ['pnl'] })
      }
      void qc.invalidateQueries({ queryKey: ['parked-orders', session.companyId, session.businessId] })
      void qc.invalidateQueries({ queryKey: ['tables', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Tables — GET /api/v1/tables?businessId=...
// ---------------------------------------------------------------------------

export function useTables(session: CompanySession) {
  return useQuery({
    queryKey: ['tables', session.companyId, session.businessId],
    queryFn: () =>
      apiFetch<TableResponse[]>('/api/v1/tables', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
    staleTime: 30_000,
  })
}

export interface CreateTableInput {
  label: string
  capacity: number
  area?: string
}

export function useCreateTable(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ label, capacity, area }: CreateTableInput) =>
      apiFetch<TableResponse>('/api/v1/tables', {
        method: 'POST',
        tenant: tenantOf(session),
        body: { businessId: session.businessId, label, capacity, area: area || null },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['tables', session.companyId, session.businessId] })
    },
  })
}

export function useActivateTable(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (tableId: string) =>
      apiFetch<TableResponse>(`/api/v1/tables/${tableId}/activate`, {
        method: 'PATCH',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['tables', session.companyId, session.businessId] })
    },
  })
}

export function useDeactivateTable(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (tableId: string) =>
      apiFetch<TableResponse>(`/api/v1/tables/${tableId}/deactivate`, {
        method: 'PATCH',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['tables', session.companyId, session.businessId] })
    },
  })
}

/** Captures a PENDING digital payment → CAPTURED, records revenue, transitions order to COMPLETED. */
export function useCapturePayment(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (paymentId: string) =>
      apiFetch<PaymentResponse>(`/api/v1/payments/${paymentId}/capture`, {
        method: 'POST',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['pnl'] })
    },
  })
}

/** Voids a CAPTURED payment — full reversal before settlement. */
export function useVoidPayment(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (paymentId: string) =>
      apiFetch<PaymentResponse>(`/api/v1/payments/${paymentId}/void`, {
        method: 'POST',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['pnl'] })
    },
  })
}

export interface RefundInput {
  paymentId: string
  amountMinor: number
  currency: string
}

/** Refunds part or all of a CAPTURED payment. */
export function useRefundPayment(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ paymentId, amountMinor, currency }: RefundInput) =>
      apiFetch<PaymentResponse>(`/api/v1/payments/${paymentId}/refund`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: { amountMinor, currency },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['pnl'] })
    },
  })
}

/** Read-path receipt for a payment (GET /api/v1/payments/{id}/receipt). */
export function useReceipt(session: CompanySession, paymentId: string | null) {
  return useQuery({
    queryKey: ['receipt', session.companyId, paymentId],
    enabled: paymentId != null,
    queryFn: () =>
      apiFetch<PaymentResponse>(`/api/v1/payments/${paymentId}/receipt`, {
        tenant: tenantOf(session),
      }),
  })
}

// ---------------------------------------------------------------------------
// Stock management — PUT /api/v1/menu/{itemId}/stock (set absolute) and
//                   POST /api/v1/menu/{itemId}/stock/add (add/subtract)
// ---------------------------------------------------------------------------

/**
 * Set absolute stock quantity for an item.
 * Pass { quantity: number } to enable tracking, { quantity: null } to make it infinite/untracked.
 */
export function useSetStock(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, quantity }: { itemId: string; quantity: number | null }) =>
      apiFetch<MenuItem>(`/api/v1/menu/${itemId}/stock`, {
        method: 'PUT',
        tenant: tenantOf(session),
        body: { quantity },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

/**
 * Add (or subtract, if negative) stock to a tracked item.
 * The backend floors at 0 and returns 422 if the item is untracked.
 */
export function useAddStock(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, amount }: { itemId: string; amount: number }) =>
      apiFetch<MenuItem>(`/api/v1/menu/${itemId}/stock/add`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: { amount },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

