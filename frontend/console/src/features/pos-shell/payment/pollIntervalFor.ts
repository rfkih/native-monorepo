/**
 * pollIntervalFor.ts — pure refetchInterval POLICY for the GATEWAY QRIS vertical-read poll (ADR
 * 0045): once a dynamic-QR charge is live, the adapter polls the vertical's OWN payment/ticket read
 * (features/pos's `useReceipt`, features/servicepos's `useTicket`) waiting for the webhook→Kafka
 * capture to land, since capture never happens client-side. Kept pure/react-query-free (the
 * pos-shell "stateless pure module" idiom — channelPicker.ts, quickChips.ts) so the backoff/stop
 * policy is unit-testable without mounting a query client.
 */

/** A payment/ticket-payment has left PENDING (captured, voided, refunded, abandoned, failed, ...) —
 *  whatever it became, there is nothing left to poll FOR; the caller decides what to DO with the
 *  terminal status (only CAPTURED triggers the receipt hand-off — see the adapter). Null/undefined
 *  (still loading, or no payment to poll yet) is NOT terminal — keep polling. */
export function isTerminalPaymentStatus(status: string | null | undefined): boolean {
  return status != null && status !== 'PENDING'
}

/**
 * 3s while healthy, backs off to 8s after a fetch error (a flaky terminal connection shouldn't
 * hammer the gateway every 3s), and stops entirely (`false`) once the status is terminal — mirrors
 * TanStack Query v5's `refetchInterval: (query) => number | false` shape; callers pass
 * `pollIntervalFor(query.state.data?.status, query.state.status === 'error')` straight through.
 */
export function pollIntervalFor(status: string | null | undefined, isError: boolean): number | false {
  if (isTerminalPaymentStatus(status)) return false
  return isError ? 8_000 : 3_000
}
