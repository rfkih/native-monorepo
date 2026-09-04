/**
 * salesHistoryApi.ts — the cashier's today's-transactions read path (owner request: see the
 * day's sales at the till and reprint a receipt).
 *
 * List: GET /api/v1/sales?businessId&from&to — the OUTLET's sales for the local day (the
 * CLIENT computes the day bounds; the server does no timezone math), newest first. Server
 * truth only: a sale rung offline appears here after its queue row syncs (the SyncCenter
 * already shows the pending queue — this list never mixes the two).
 *
 * Reprint: GET /api/v1/orders/{id} — the full order (lines, modifiers, breakdown, payment),
 * fed to the SAME ReceiptView the post-payment overlay uses, so a reprint is byte-identical
 * to the original apart from the header time + the reprint marker row.
 */
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { OrderResponse } from './api'

/** Mirror of restaurant-service SaleHistoryResponse. */
export interface SaleHistoryRow {
  saleId: string
  /** Null for legacy/no-order sales — those rows list but cannot rebuild a receipt. */
  orderId: string | null
  occurredAt: string
  amountMinor: number
  currency: string
  tenderType: string | null
  channelCode: string | null
  /** 'CAPTURED' | 'VOIDED' | 'REFUNDED' | 'PARTIALLY_REFUNDED' | null — null on a row from before
   *  this field existed, or a legacy/no-order sale, so a stale cache never crashes a reader (same
   *  optional-field convention as `billId` in api.ts). */
  paymentStatus?: string | null
  /** The settling payment's cumulative refunded amount in minor units — 0 when nothing was
   *  refunded or no payment backs the sale (server COALESCEs); optional only for stale caches.
   *  Feeds `netSaleAmountMinor` so the day total subtracts refunds. */
  refundedMinor?: number
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/** The outlet-local calendar-day bounds around `now`, as ISO instants. */
export function localDayBounds(now: Date): { from: string; to: string; dayKey: string } {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  const dayKey = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}-${String(
    start.getDate(),
  ).padStart(2, '0')}`
  return { from: start.toISOString(), to: end.toISOString(), dayKey }
}

/** Today's sales for the outlet — only fetched while the history sheet is open. */
export function useSalesHistory(session: CompanySession, enabled: boolean) {
  const { from, to, dayKey } = localDayBounds(new Date())
  return useQuery({
    enabled,
    queryKey: ['salesHistory', session.companyId, session.businessId, dayKey],
    // The list must reflect sales rung seconds ago on ANOTHER device too — keep it fresh
    // while open, but never poll while closed (enabled gates the whole query).
    refetchInterval: enabled ? 30_000 : false,
    staleTime: 10_000,
    queryFn: () =>
      apiFetch<SaleHistoryRow[]>('/api/v1/sales', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId, from, to },
      }),
  })
}

/**
 * The outlet's sales over an EXPLICIT window `[from, to)` — the manager/owner past closed-day
 * transaction drill-down (the window is a closed session's `[openedAt, closedAt)`). A closed day is
 * immutable, so unlike {@link useSalesHistory} there is no polling; the server still caps the list at
 * its newest 200 rows. Only fetched while a past day is open (`enabled` + both bounds present).
 */
export function useSalesHistoryWindow(
  session: CompanySession,
  from: string | null,
  to: string | null,
  enabled: boolean,
) {
  return useQuery({
    enabled: enabled && from != null && to != null,
    queryKey: ['salesHistory', session.companyId, session.businessId, from, to],
    staleTime: 5 * 60_000,
    queryFn: () =>
      apiFetch<SaleHistoryRow[]>('/api/v1/sales', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId, from: from!, to: to! },
      }),
  })
}

/** One order with lines + payment + breakdown — the reprint's receipt source. */
export function useOrderReceipt(session: CompanySession, orderId: string | null) {
  return useQuery({
    enabled: orderId != null,
    queryKey: ['orderReceipt', session.companyId, orderId],
    staleTime: 5 * 60_000,
    queryFn: () =>
      apiFetch<OrderResponse>(`/api/v1/orders/${orderId}`, {
        tenant: tenantOf(session),
      }),
  })
}
