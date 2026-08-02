/**
 * usePaymentAttempt — THE single minting site for a payment idempotency key (redesign P3).
 *
 * Lifecycle contract (do not change without re-reading ADR 0006 + promotions review W2 /
 * carwash review W1): one key per payment ATTEMPT = per PANEL MOUNT, held in state and reused
 * across retries — a retry after an ambiguous failure replays the same key and resolves to the
 * same order/ticket (never a second charge or coupon redemption). Closing the panel and paying
 * again is a NEW attempt (new mount → new key), which is what makes pay→cancel→pay create two
 * orders while retry-after-network-error creates one.
 *
 * Bill checks do NOT use this hook: BillDetail mints a fresh key per pay-initiation
 * (freshIdempotencyKey) and passes it down as a prop — that exception is deliberate (a split
 * check's attempt starts when the cashier taps Pay in the sheet, not when the modal mounts).
 */
import { useState } from 'react'

export function usePaymentAttempt(): string {
  const [key] = useState<string>(() => crypto.randomUUID())
  return key
}
