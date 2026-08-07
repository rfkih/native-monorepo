/**
 * displayChannel.ts — Phase 6 (ADR 0029) typed protocol for the POS ↔ customer-display
 * BroadcastChannel, one channel per outlet: `pos-display:{outletId}`.
 *
 * BroadcastChannel is same-origin, same-browser only (no cross-device delivery) — the customer
 * display is a SECOND browser tab/window on the SAME terminal (e.g. a second monitor), opened via
 * the "Customer display" button in Pos.tsx (see displayPublisher.ts), not a remote device.
 *
 * Money rule (rule 8): every amount crossing the channel is the same integer-minor-units +
 * ISO-4217 currency shape the rest of the app uses — the receiver (CustomerDisplay.tsx) formats it
 * locally via formatMoney(), never a pre-formatted string (that would bake in the PUBLISHER's
 * locale, defeating the display's own independent locale toggle).
 */

export interface DisplayMoney {
  amountMinor: number
  currency: string
}

/**
 * One cart/bill line as shown on the customer-facing screen. `name` is BUSINESS data (a menu item
 * name as entered by the merchant) — never a translated UI string — so it crosses the channel
 * verbatim, exactly like the receipt/KOT views already render item names directly.
 */
export interface DisplayLine {
  name: string
  qty: number
  unitPriceMinor: number
  lineTotalMinor: number
}

export interface DisplayBreakdown {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  grandTotalMinor: number
  currency: string
}

/**
 * The QR actually on screen at the till, mirrored to the customer display (ADR 0045):
 *  - `STATIC` — the merchant's own uploaded QRIS image. The display fetches the image bytes
 *    itself (see `CustomerDisplay.tsx`'s `PaymentQrScreen`) rather than carrying them over the
 *    channel — a `postMessage` payload is a poor place for a multi-hundred-KB image blob, and the
 *    display already has a live, authenticated session.
 *  - `GATEWAY` — the dynamic Midtrans `qrString`, rendered client-side via the same `QrCanvas` the
 *    till panel uses, plus its expiry so the display can run an identical countdown.
 */
export type PaymentQrKind =
  | { kind: 'STATIC' }
  | { kind: 'GATEWAY'; qrString: string; expiresAt: string }

export type DisplayMessage =
  | { type: 'CART_UPDATED'; lines: DisplayLine[]; breakdown: DisplayBreakdown | null }
  | { type: 'PAYMENT_STARTED'; due: DisplayMoney }
  /** ADR 0045: a QR is now visible on the till (STATIC or GATEWAY) — the customer scans THIS
   *  screen instead of a printed/counter code. Reverts to PAYMENT_STARTED on cancel/expiry. */
  | { type: 'PAYMENT_QR'; due: DisplayMoney; qr: PaymentQrKind }
  | { type: 'PAYMENT_COMPLETED'; change: DisplayMoney }
  | { type: 'IDLE' }

/** The channel name is namespaced per outlet — two outlets on the same terminal (rare, but the
 * outlet picker allows switching) never cross-talk. */
export function channelName(outletId: string): string {
  return `pos-display:${outletId}`
}

// ---------------------------------------------------------------------------
// Runtime validation — a BroadcastChannel delivers whatever any same-origin script posts, so the
// receiver defensively parses every inbound MessageEvent.data before trusting its shape (a stray
// message from an unrelated feature, or a future/older app version, must never crash the
// customer-facing screen).
// ---------------------------------------------------------------------------

export function isDisplayMessage(value: unknown): value is DisplayMessage {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  switch (v.type) {
    case 'CART_UPDATED':
      return (
        Array.isArray(v.lines) &&
        v.lines.every(isDisplayLine) &&
        (v.breakdown === null || v.breakdown === undefined || isDisplayBreakdown(v.breakdown))
      )
    case 'PAYMENT_STARTED':
      return isDisplayMoney(v.due)
    case 'PAYMENT_QR':
      return isDisplayMoney(v.due) && isPaymentQrKind(v.qr)
    case 'PAYMENT_COMPLETED':
      return isDisplayMoney(v.change)
    case 'IDLE':
      return true
    default:
      return false
  }
}

function isPaymentQrKind(value: unknown): value is PaymentQrKind {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  if (v.kind === 'STATIC') return true
  if (v.kind === 'GATEWAY') return typeof v.qrString === 'string' && typeof v.expiresAt === 'string'
  return false
}

function isDisplayMoney(value: unknown): value is DisplayMoney {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v.amountMinor === 'number' && typeof v.currency === 'string'
}

function isDisplayLine(value: unknown): value is DisplayLine {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.name === 'string' &&
    typeof v.qty === 'number' &&
    typeof v.unitPriceMinor === 'number' &&
    typeof v.lineTotalMinor === 'number'
  )
}

function isDisplayBreakdown(value: unknown): value is DisplayBreakdown {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.subtotalMinor === 'number' &&
    typeof v.discountMinor === 'number' &&
    typeof v.serviceChargeMinor === 'number' &&
    typeof v.taxMinor === 'number' &&
    typeof v.grandTotalMinor === 'number' &&
    typeof v.currency === 'string'
  )
}
