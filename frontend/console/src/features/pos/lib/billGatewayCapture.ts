/**
 * billGatewayCapture.ts — pure decision helpers for `BillPaymentModal.tsx`'s
 * `BillGatewayDigitalAttempt` (ADR 0045 extension to bills, full-bill only). Kept fetch-free/
 * React-free (the `effectiveMode.ts`/`pollIntervalFor.ts`/`chargePhase.ts` idiom) so the two
 * decisions a live gateway attempt makes are unit-testable without mounting the component:
 *
 *  1. {@link isCaptureObserved} — the capture-race gate. BOTH the vertical-read poll (`useReceipt`)
 *     and a manual "Mark as paid" (`useCapturePayment`) can independently observe CAPTURED; this
 *     decides whether THIS observation should be the one that fires `onSuccess` — guards the
 *     component's `capturedRef` against firing twice on a race between the two.
 *
 *  2. {@link isCleanCancel} — whether a resolved charge-cancel is a genuine clean cancel (→ abandon
 *     the bill's PENDING payment, releasing its line reservation, and fall back to the initiate
 *     view) or a capture-in-flight race (→ leave everything as-is; the vertical-read poll is about
 *     to hand off to `onSuccess`). "Captured beats cancel" — the same rule PaymentModal.tsx's
 *     `handleGatewayCancel` and `chargePhase.ts` already encode for the order flow; unlike the order
 *     flow, a bill's clean cancel additionally abandons the PENDING payment itself (not just the
 *     charge) — see `BillGatewayDigitalAttempt`'s class doc for why.
 */

/** True exactly when THIS observation of `status` should be treated as the capture — i.e. it
 *  reports CAPTURED and no earlier observation (poll or manual confirm) already claimed it. */
export function isCaptureObserved(status: string | null | undefined, alreadyCaptured: boolean): boolean {
  return status === 'CAPTURED' && !alreadyCaptured
}

/** True when a resolved charge-cancel is a genuine clean cancel (abandon + fall back to initiate),
 *  false when it's a capture-in-flight race (leave the pending payment alone; the poll takes over).
 *  A capture already observed by an earlier race (`alreadyCaptured`) is never a clean cancel either
 *  — there is nothing left to abandon. */
export function isCleanCancel(capturedInFlight: boolean, alreadyCaptured: boolean): boolean {
  return !capturedInFlight && !alreadyCaptured
}
