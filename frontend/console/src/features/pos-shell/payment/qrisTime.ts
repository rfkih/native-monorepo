/**
 * qrisTime.ts — pure GATEWAY-QRIS countdown helpers (ADR 0045), shared by `QrisPanelViews.tsx`
 * (the till's own countdown chip) and `features/pos/display/CustomerDisplay.tsx` (the customer-facing
 * mirror). No React/fetch — the pos-shell "stateless pure module" idiom (channelPicker.ts,
 * quickChips.ts): callers supply `nowMs` explicitly (`Date.now()`) so this stays trivially testable.
 *
 * The countdown clock face is deliberately NOT locale-formatted via Intl — a raw `m:ss` digit
 * readout is a stopwatch, not a date/number (rule 9 governs user-facing COPY; this is a numeral
 * convention every locale reads the same way, like a timer on a microwave).
 */

/** Milliseconds remaining until `expiresAtMs`, floored at 0 — never negative. */
export function msLeft(expiresAtMs: number, nowMs: number): number {
  return Math.max(0, expiresAtMs - nowMs)
}

/** True once the deadline has passed — inclusive (`nowMs === expiresAtMs` counts as expired). */
export function isExpired(expiresAtMs: number, nowMs: number): boolean {
  return nowMs >= expiresAtMs
}

/** Formats a millisecond duration as `m:ss` (e.g. `14:59`, `0:07`, `0:00` once expired). */
export function formatCountdown(msRemaining: number): string {
  const totalSeconds = Math.max(0, Math.floor(msRemaining / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}
