/**
 * chargePhase.ts — pure GATEWAY-QRIS charge lifecycle machine (ADR 0045). Mirrors the
 * `effectiveMode.ts` idiom: NO react-query/fetch dependency (pure logic + types) so it is unit
 * testable in isolation and `features/payments/api.ts` can import `ChargeStatus` FROM here (never
 * the reverse) — the same "types live in the pure module, the API client re-exports them" split
 * `effectiveMode.ts`/`QrisMode` already established.
 *
 * The five terminal-ish UI states a GATEWAY QR panel can be in, plus `idle` (no live charge — the
 * panel falls back to the vertical's manual "Mark as paid" flow, e.g. after a clean cancel or a
 * CANCELED charge status):
 *
 *   idle → creating → active ⇄ (countdown/server EXPIRED) → expired
 *                        ↓ CANCEL_STARTED                     ↓ NEW_QR (retry/new-QR tap)
 *                   cancelling                              creating
 *                        ↓ CHARGE_STATUS
 *              SUCCEEDED → active (capture beats cancel — see below)
 *              else       → idle
 *
 * `useGatewayQris.ts` is the only caller; this module never imports React.
 */

export type ChargePhase = 'idle' | 'creating' | 'active' | 'expired' | 'error' | 'cancelling'

/** Mirrors payment-service's `ChargeStatus` enum verbatim (ADR 0045). */
export type ChargeStatus = 'INITIATED' | 'QR_ISSUED' | 'SUCCEEDED' | 'EXPIRED' | 'CANCELED' | 'FAILED'

export type ChargePhaseEvent =
  /** A create (or retry/new-QR) call was just fired — mints a fresh Idempotency-Key attempt. */
  | { type: 'CREATE_STARTED' }
  /** The create call itself failed (network/ApiError) before any charge row was even returned. */
  | { type: 'CREATE_FAILED' }
  /** A charge response was received from ANY of create/sync/cancel — the `status` field is the
   * single source of truth for where the panel should be. */
  | { type: 'CHARGE_STATUS'; status: ChargeStatus }
  /** The client-side 1s countdown ticker reached `charge.expiresAt`. */
  | { type: 'COUNTDOWN_EXPIRED' }
  /** The cashier tapped Cancel (or the modal's X while a charge is live) — the cancel call is now
   * in flight. */
  | { type: 'CANCEL_STARTED' }
  /** The cancel call itself failed (network/ApiError) — the charge's true state is unknown; surface
   * an error rather than silently falling back (a live charge left un-cancelled at Midtrans must
   * not look like it quietly went away). */
  | { type: 'CANCEL_FAILED' }

/**
 * Pure transition function — `useGatewayQris` is the only stateful caller (`useState` + this
 * reducer), which is what makes the phase testable without React: every scenario is just a
 * `(phase, event) → phase` assertion.
 */
export function nextChargePhase(phase: ChargePhase, event: ChargePhaseEvent): ChargePhase {
  switch (event.type) {
    case 'CREATE_STARTED':
      return 'creating'
    case 'CREATE_FAILED':
      return 'error'
    case 'CANCEL_STARTED':
      return phase === 'active' || phase === 'expired' ? 'cancelling' : phase
    case 'CANCEL_FAILED':
      return 'error'
    case 'COUNTDOWN_EXPIRED':
      return phase === 'active' ? 'expired' : phase
    case 'CHARGE_STATUS':
      // Captured-beats-cancel: a cancel resolved (or a status arrived) while we were cancelling —
      // if Midtrans actually settled the money mid-cancel (SUCCEEDED), stay/return to `active` so
      // the caller keeps polling the vertical read toward the receipt instead of treating this as
      // a clean cancel. Any OTHER status while cancelling (CANCELED, EXPIRED, ...) is a genuine
      // cancel — drop to `idle` so the panel falls back to the manual "Mark as paid" flow.
      if (phase === 'cancelling' && event.status !== 'SUCCEEDED') return 'idle'
      return statusPhase(event.status)
    default:
      return phase
  }
}

function statusPhase(status: ChargeStatus): ChargePhase {
  switch (status) {
    case 'INITIATED':
    case 'QR_ISSUED':
    case 'SUCCEEDED':
      // SUCCEEDED still renders as `active`: the CHARGE settled, but capture is a SEPARATE,
      // asynchronous step (the vertical's Kafka consumer) — the caller's vertical-read poll is what
      // actually closes the panel once the payment itself turns CAPTURED (see useGatewayQris.ts).
      return 'active'
    case 'EXPIRED':
      return 'expired'
    case 'CANCELED':
      return 'idle'
    case 'FAILED':
      return 'error'
  }
}
