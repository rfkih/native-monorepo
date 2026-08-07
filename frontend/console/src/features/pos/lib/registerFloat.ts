/**
 * registerFloat.ts — the open-drawer float default (ADR 0036 UX amendment, owner request):
 * "the float should be filled at start of the day, defaulting to the last day's cash count."
 * Pure logic (no react-query/fetch) so it is unit-testable in isolation — the effectiveMode.ts
 * idiom.
 *
 * The cash-stays-in-drawer-overnight model: yesterday's CLOSED session's counted cash is the most
 * likely opening float today. It is a PREFILL ONLY — the cashier edits it freely (money was
 * banked, a different float was placed), and the server still records whatever is submitted.
 */
import { isoMinorExponent } from '@/lib/money'
import type { RegisterSessionResponse } from '../registerApi'

/**
 * Picks the float-default source from the session history (most-recent-first, as the server
 * returns it): the newest CLOSED session that actually recorded a count. Null when the outlet has
 * never closed a drawer.
 */
export function lastClosedSession(
  rows: readonly RegisterSessionResponse[] | null | undefined,
): RegisterSessionResponse | null {
  if (!rows) return null
  return rows.find((r) => r.status === 'CLOSED' && r.countedCashMinor != null) ?? null
}

/**
 * Renders a minor-units amount as the MAJOR-unit string a money `<input>` holds (the inverse of
 * `parseDiscountInput`): IDR (exponent 0) passes through unscaled; exponent-2 currencies render
 * with their decimals (rule 8 — the exponent comes from ISO-4217, never guessed).
 */
export function minorToMajorInput(minor: number, currency: string): string {
  const exp = isoMinorExponent(currency)
  if (exp === 0) return String(minor)
  return (minor / 10 ** exp).toFixed(exp)
}
