import { useMutation, useQuery, useQueryClient, keepPreviousData, type UseQueryOptions } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import { stashCatalog } from './offline/catalogCache'
import type { EffectiveRulesResponse } from './offline/provisionalPricing'

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
  /**
   * Phase 6 (ADR 0029): where the order was placed — 'POS' (the cashier terminal, the pre-existing
   * default) or 'SELF_ORDER' (a customer scanning a table/kiosk QR code — see
   * features/pos/selfOrderApi.ts). Optional/undefined on any response that predates this field so a
   * stale cache never crashes the tray; ParkedTray treats a missing value as 'POS'.
   */
  source?: 'POS' | 'SELF_ORDER'
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
  /**
   * Cost per unit in minor units (ADR 0038 phase 3 — inventory stocktake) — null when the item
   * has no cost on file. Only items with BOTH a tracked stockQuantity and a unitCostMinor produce
   * a valued shrinkage line at stocktake; untracked/uncosted items are informational only.
   */
  unitCostMinor: number | null
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
 *
 * Phase 4 (ADR 0027, additive): `discountMinor` is PROMO-ONLY — a loyalty-points redemption is
 * reported separately as `loyaltyRedeemedMinor` (contra-revenue). `giftCardAppliedMinor` is the
 * amount TENDERED from a gift card (never a discount) and `residualDueMinor = grandTotalMinor -
 * giftCardAppliedMinor` is what remains for the requested payment method to actually authorize.
 * All three default to 0 on every pre-Phase-4 response.
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
  /** Phase 4 (ADR 0027): currency value of loyalty points redeemed, minor units; 0 when none. */
  loyaltyRedeemedMinor: number
  /** Phase 4 (ADR 0027): amount TENDERED from a gift card, minor units; 0 when none. */
  giftCardAppliedMinor: number
  /** Phase 4 (ADR 0027): grandTotalMinor - giftCardAppliedMinor — what remains to authorize. */
  residualDueMinor: number
}

/** Matches backend PaymentResponse record (ADR 0006). All money is integer minor units. */
export interface PaymentResponse {
  paymentId: string
  /** Null for a bill-originated payment (ADR 0045 extension to bills — a bill is not an order); set
   *  for an order-originated payment. See `billId`, its bill-side counterpart. */
  orderId: string | null
  /** ADR 0045 extension to bills: set for a bill-originated payment (`/bills/{id}/pay-pending` and
   *  its `/receipt`); null for an order-originated payment. Optional/undefined on any response that
   *  predates this field so a stale cache never crashes a reader. */
  billId?: string | null
  /** String name of TenderType enum: CASH | QRIS | CARD | ONLINE */
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
  /** Cumulative amount refunded so far, minor units. Optional/undefined on any response that
   *  predates this field (same stale-cache convention as `billId` above) — treat as 0 when absent. */
  refundedMinor?: number
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

/** Payment block sent on checkout — tenderType must be CASH | QRIS | CARD | ONLINE. */
export interface CheckoutPaymentInput {
  tenderType: 'CASH' | 'QRIS' | 'CARD' | 'ONLINE'
  /** Required for CASH: the physical amount handed over in minor units. Ignored for digital/ONLINE. */
  tenderedMinor?: number
  /**
   * Required for ONLINE — the picked sales channel's immutable UPPERCASE code (ADR 0036 Phase B).
   * Sent ONLY with tenderType 'ONLINE'; the server rejects ONLINE combined with a gift-card or
   * loyalty-points redemption, or a missing/inactive channel.
   */
  channelCode?: string
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
 *
 * Phase 4 (ADR 0027): loyaltyMemberId/loyaltyRedeemPoints (optional) preview a points redemption —
 * the quote NEVER throws for an unknown member or an insufficient balance (silently previews a
 * clamped/zero redemption; only checkout is fail-closed). giftCardId/giftCardRedeemMinor are
 * deliberately NOT threaded here — the payment modal computes `residualDueMinor` client-side from
 * the identical `grandTotalMinor - giftCardRedeemMinor` arithmetic the server's own factory uses
 * (see components/GiftCardField.tsx), so a gift card never needs a second quote round-trip.
 */
export function useQuote(
  session: CompanySession,
  lines: OrderLineInput[],
  discountMinor: number,
  couponCode: string | null = null,
  loyaltyMemberId: string | null = null,
  loyaltyRedeemPoints: number = 0,
  /** Phase 5 (ADR 0028): the caller passes `!offline` — no point firing a quote that can only fail
   * while the terminal is offline (Pos.tsx computes a provisional breakdown locally instead). */
  enabledOverride: boolean = true,
) {
  // State-based debounce: both the query key and the request body read from `debounced`,
  // so they are always identical and the server always receives the full current cart.
  const [debounced, setDebounced] = useState<{
    lines: OrderLineInput[]
    discountMinor: number
    couponCode: string | null
    loyaltyMemberId: string | null
    loyaltyRedeemPoints: number
  }>(() => ({ lines, discountMinor, couponCode, loyaltyMemberId, loyaltyRedeemPoints }))
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      setDebounced({ lines, discountMinor, couponCode, loyaltyMemberId, loyaltyRedeemPoints })
    }, 400)
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [lines, discountMinor, couponCode, loyaltyMemberId, loyaltyRedeemPoints])

  // Derived (no state): the debounce is still holding newer inputs than the query key sees.
  // Reference-compare is sound — `lines` is the caller's memoized cartLines, the rest primitives.
  const debouncePending =
    lines !== debounced.lines ||
    discountMinor !== debounced.discountMinor ||
    couponCode !== debounced.couponCode ||
    loyaltyMemberId !== debounced.loyaltyMemberId ||
    loyaltyRedeemPoints !== debounced.loyaltyRedeemPoints

  const enabled = debounced.lines.length > 0 && enabledOverride

  const query = useQuery({
    queryKey: [
      'quote',
      session.companyId,
      session.businessId,
      debounced.lines,
      debounced.discountMinor,
      debounced.couponCode,
      debounced.loyaltyMemberId,
      debounced.loyaltyRedeemPoints,
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
          loyaltyMemberId: debounced.loyaltyMemberId || null,
          loyaltyRedeemPoints: debounced.loyaltyRedeemPoints > 0 ? debounced.loyaltyRedeemPoints : null,
        },
      }),
  })

  return {
    ...query,
    /**
     * True while the shown total may still belong to the PREVIOUS cart state (debounce window +
     * in-flight fetch under keepPreviousData) — the UI dims it (UX audit: a fresh item count next
     * to a stale amount reads as a wrong bill).
     */
    refreshing: lines.length > 0 && enabledOverride && (debouncePending || query.isFetching),
  }
}

/** One row of the best-seller ranking (GET /api/v1/orders/item-popularity). */
export interface ItemPopularityResponse {
  menuItemId: string
  soldQty: number
}

/**
 * Units sold per menu item (completed walk-in orders + paid bill lines), best-sellers first —
 * the "All" tab sorts by this. Refreshes lazily (5 min stale time); offline or failed → the
 * caller falls back to the unsorted order.
 */
export function useItemPopularity(session: CompanySession) {
  return useQuery({
    queryKey: ['item-popularity', session.companyId, session.businessId],
    staleTime: 5 * 60_000,
    queryFn: () =>
      apiFetch<ItemPopularityResponse[]>('/api/v1/orders/item-popularity', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

/** One row of the per-item sales breakdown over a window (GET /api/v1/orders/item-sales). */
export interface ItemSalesResponse {
  menuItemId: string
  /** Sold-time name snapshot — survives a menu rename/delete. */
  name: string
  soldQty: number
  /** Gross revenue for the item, minor units (sum of line totals). */
  revenueMinor: number
}

/**
 * Per-menu-item units sold + gross revenue whose SALE occurred in [from, to), best-sellers first —
 * the Z-report items section (the register session's window) and the stock-opname "sold today"
 * reference (the local-day window). `from`/`to` are ISO instants; disabled until both are known.
 */
export function useItemSales(
  session: CompanySession,
  from: string | null,
  to: string | null,
  enabled = true,
) {
  return useQuery({
    enabled: enabled && from != null && to != null,
    queryKey: ['itemSales', session.companyId, session.businessId, from, to],
    staleTime: 60_000,
    queryFn: () =>
      apiFetch<ItemSalesResponse[]>('/api/v1/orders/item-sales', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId, from: from ?? undefined, to: to ?? undefined },
      }),
  })
}

export function useMenu(session: CompanySession) {
  return useQuery({
    queryKey: ['menu', session.companyId, session.businessId],
    queryFn: async () => {
      const result = await apiFetch<MenuItem[]>('/api/v1/menu', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      })
      // Write-through offline cache (Phase 5, ADR 0028): best-effort, never blocks the online read.
      if (result) void stashCatalog(session.companyId, 'restaurant', 'menu', result)
      return result
    },
  })
}

export function useCategories(session: CompanySession) {
  return useQuery({
    queryKey: ['menu-categories', session.companyId, session.businessId],
    queryFn: async () => {
      const result = await apiFetch<CategoryResponse[]>('/api/v1/menu/categories', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      })
      if (result) void stashCatalog(session.companyId, 'restaurant', 'menuCategories', result)
      return result
    },
  })
}

// ---------------------------------------------------------------------------
// Effective pricing rules — GET /api/v1/pricing/effective-rules?businessId=
// Phase 5 (ADR 0028): stashed into the offline catalog cache so an offline sale can compute a
// provisional price breakdown from the last known rates (features/pos/offline/provisionalPricing).
// ---------------------------------------------------------------------------

export function useEffectiveRules(session: CompanySession) {
  return useQuery({
    queryKey: ['pricing-effective-rules', session.companyId, session.businessId],
    queryFn: async () => {
      const raw = await apiFetch<EffectiveRulesResponse>('/api/v1/pricing/effective-rules', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      })
      if (!raw) return raw
      // The backend's `currency` is nullable (no rule seeded for this business at all) — the
      // company's base currency is always the correct fallback (rule 8: every amount needs one).
      const result: EffectiveRulesResponse = { ...raw, currency: raw.currency ?? session.baseCurrency }
      void stashCatalog(session.companyId, 'restaurant', 'effectiveRules', result)
      return result
    },
    staleTime: 5 * 60_000,
  })
}

export interface CheckoutInput {
  /**
   * Minted ONCE when the payment attempt begins (panel mount) and REUSED across retries of the
   * same attempt — never per click. If the first attempt commits server-side but the response is
   * lost, the retry replays the same key and resolves to the SAME order instead of charging (and,
   * since ADR 0026, redeeming a coupon) twice. The BillDetail freshIdempotencyKey pattern;
   * promotions review W2.
   */
  idempotencyKey: string
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
  /** Phase 4 (ADR 0027): optional loyalty member to attach (earn attribution and/or the identity a
   * points redemption is charged against). */
  loyaltyMemberId?: string | null
  /** Phase 4 (ADR 0027): optional points to redeem; requires loyaltyMemberId. Clamped server-side. */
  loyaltyRedeemPoints?: number | null
  /** Phase 4 (ADR 0027): optional gift card to redeem as a TENDER (never a discount). */
  giftCardId?: string | null
  /** Phase 4 (ADR 0027): optional amount to redeem from the gift card, minor units. Clamped
   * server-side. Requires giftCardId. */
  giftCardRedeemMinor?: number | null
}

export function useCheckout(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      idempotencyKey,
      lines,
      payment,
      discountMinor,
      orderType,
      tableId,
      couponCode,
      loyaltyMemberId,
      loyaltyRedeemPoints,
      giftCardId,
      giftCardRedeemMinor,
    }: CheckoutInput) =>
      apiFetch<OrderResponse>('/api/v1/orders', {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          idempotencyKey,
          lines,
          payment: payment ?? null,
          discountMinor: discountMinor && discountMinor > 0 ? discountMinor : null,
          orderType: orderType ?? null,
          tableId: tableId ?? null,
          couponCode: couponCode || null,
          loyaltyMemberId: loyaltyMemberId || null,
          loyaltyRedeemPoints: loyaltyRedeemPoints && loyaltyRedeemPoints > 0 ? loyaltyRedeemPoints : null,
          giftCardId: giftCardId || null,
          giftCardRedeemMinor: giftCardRedeemMinor && giftCardRedeemMinor > 0 ? giftCardRedeemMinor : null,
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
  /** Phase 4 (ADR 0027): loyalty/gift-card redemption is likewise evaluated HERE, not at park time
   * — see CheckoutInput's twin fields. */
  loyaltyMemberId?: string | null
  loyaltyRedeemPoints?: number | null
  giftCardId?: string | null
  giftCardRedeemMinor?: number | null
}

export function usePayParked(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      orderId,
      payment,
      couponCode,
      loyaltyMemberId,
      loyaltyRedeemPoints,
      giftCardId,
      giftCardRedeemMinor,
    }: PayParkedInput) =>
      apiFetch<OrderResponse>(`/api/v1/orders/${orderId}/pay`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          payment: payment ?? null,
          couponCode: couponCode || null,
          loyaltyMemberId: loyaltyMemberId || null,
          loyaltyRedeemPoints: loyaltyRedeemPoints && loyaltyRedeemPoints > 0 ? loyaltyRedeemPoints : null,
          giftCardId: giftCardId || null,
          giftCardRedeemMinor: giftCardRedeemMinor && giftCardRedeemMinor > 0 ? giftCardRedeemMinor : null,
        },
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

/**
 * Voids a CAPTURED payment — full reversal before settlement. Manager-gated at the gateway alongside
 * refund (ADR 0061, SALE_REVERSAL_ROLES), so it rides the PERSONAL/elevated bearer for the same
 * reason (see useRefundPayment). Currently unused by the UI, which standardizes on refund for the
 * customer-return case.
 */
export function useVoidPayment(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (paymentId: string) =>
      apiFetch<PaymentResponse>(`/api/v1/payments/${paymentId}/void`, {
        method: 'POST',
        tenant: tenantOf(session),
        auth: 'personal',
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

/**
 * Refunds part or all of a CAPTURED payment. Manager-gated at the gateway (ADR 0061,
 * SALE_REVERSAL_ROLES) — sent on the PERSONAL bearer (`auth: 'personal'`) so an owner/manager
 * identity is what the gateway authorizes: on a normal login that IS the login's token; on a shared
 * device terminal it is the owner/manager ELEVATION token (ADR 0049 P3b), never the outlet
 * credential a cashier holds.
 */
export function useRefundPayment(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ paymentId, amountMinor, currency }: RefundInput) =>
      apiFetch<PaymentResponse>(`/api/v1/payments/${paymentId}/refund`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: { amountMinor, currency },
        auth: 'personal',
      }),
    onSuccess: () => {
      // The sale is now reversed — refresh the surfaces that show its state. The register/daily
      // read models update asynchronously off SaleRefunded, so this only refreshes what the client
      // can re-read directly (P&L, today's-sales list, and the reprinted receipt/order).
      void qc.invalidateQueries({ queryKey: ['pnl'] })
      void qc.invalidateQueries({ queryKey: ['salesHistory'] })
      void qc.invalidateQueries({ queryKey: ['orderReceipt'] })
      void qc.invalidateQueries({ queryKey: ['receipt'] })
    },
  })
}

/**
 * Read-path receipt for a payment (GET /api/v1/payments/{id}/receipt).
 *
 * `options.refetchInterval` (ADR 0045): GATEWAY QRIS re-uses this SAME read as its capture poll —
 * the vertical never captures client-side, so `RestaurantDigitalAttempt` polls this endpoint (via
 * `pollIntervalFor.ts`'s pure backoff/stop policy) until `status` turns CAPTURED, then hands off to
 * the identical onSuccess/receipt path "Mark as paid" already uses. Passed straight through to
 * `useQuery` — undefined (every pre-ADR-0045 caller) keeps today's one-shot behavior.
 */
export function useReceipt(
  session: CompanySession,
  paymentId: string | null,
  options: Pick<UseQueryOptions<PaymentResponse | null>, 'refetchInterval'> = {},
) {
  return useQuery({
    queryKey: ['receipt', session.companyId, paymentId],
    enabled: paymentId != null,
    refetchInterval: options.refetchInterval,
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

