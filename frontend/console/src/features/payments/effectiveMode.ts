/**
 * Pure QRIS-mode resolution (ADR 0045) — decides what the till should actually treat as the
 * effective QRIS payment mode, given the settings the effective-settings endpoint returned,
 * whether the terminal is offline, and the sale's currency. Deliberately has NO react-query/fetch
 * dependency (pure logic + types) so it can be unit tested in isolation — the same idiom as
 * `features/pos-shell/payment/channelPicker.ts`. `features/payments/api.ts` imports the
 * `QrisMode`/`EffectiveSettings` types FROM here (never the reverse), so this module never
 * depends on the API client.
 *
 * Fails OPEN to MANUAL (today's behavior, unchanged) whenever the till cannot trust a richer
 * mode:
 *  - settings missing/loading/errored, or the terminal is offline — a digital QR mode is
 *    meaningless without a live, trustworthy read.
 *  - GATEWAY additionally requires the sale currency to be IDR (QRIS/Midtrans is IDR-only) AND a
 *    connected provider — an unconfigured or disconnected gateway must never strand the cashier
 *    mid-sale.
 *  - STATIC additionally requires the resolved settings to actually have an image on file
 *    (`staticQrAvailable`) — a company/outlet switched to STATIC with nothing uploaded yet must
 *    not show a blank/broken QR slot.
 */

export type QrisMode = 'MANUAL' | 'STATIC' | 'GATEWAY'

/** Mirrors the `gateway` block of GET /api/v1/payment-settings/effective. */
export interface EffectiveGateway {
  provider: 'MIDTRANS'
  environment: 'SANDBOX' | 'PRODUCTION'
  connected: boolean
}

/** Mirrors GET /api/v1/payment-settings/effective?businessId=<uuid> (ADR 0045). */
export interface EffectiveSettings {
  mode: QrisMode
  staticQrAvailable: boolean
  gateway: EffectiveGateway | null
}

/**
 * Resolves the mode the till should actually render for this sale. `settings` is the effective
 * query's data (undefined while loading or on a 404-shaped gap); `isError` is the query's own
 * `isError` (a network/5xx failure — distinct from "not yet loaded"); `offline` is the terminal's
 * offline flag; `currency` is the sale's currency (rule 8 — always compared against the ISO code,
 * never inferred).
 */
export function effectiveQrisMode(
  settings: EffectiveSettings | undefined,
  isError: boolean,
  offline: boolean,
  currency: string,
): QrisMode {
  if (offline || isError || !settings) return 'MANUAL'
  switch (settings.mode) {
    case 'GATEWAY':
      return currency === 'IDR' && settings.gateway?.connected ? 'GATEWAY' : 'MANUAL'
    case 'STATIC':
      return settings.staticQrAvailable ? 'STATIC' : 'MANUAL'
    default:
      return 'MANUAL'
  }
}
