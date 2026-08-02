/**
 * Pure helpers for the platform-settlement form (ADR 0036 Phase C2): major-unit input parsing and
 * the fee derivation — split out (no React, no network) so they are independently unit-tested,
 * mirroring features/expenses/amount.ts's parseAmountInputToMinor / features/pos/lib/discountInput.ts.
 */

import { isoMinorExponent } from '@/lib/money'

/**
 * Parses a user-typed amount (major units, e.g. "45000" typed for IDR, or "12.50" for USD) into
 * integer minor units for the given currency (rule 8 — money on the wire is always integer minor
 * units). Returns `null` for anything that is not a finite number in range (blank input, "abc",
 * a negative amount, or — unless `allowZero` — zero).
 *
 * `allowZero` defaults to false (the gross-settled field: the backend requires it `@Positive`).
 * The net-received field passes `allowZero: true` — a payout entirely eaten by platform fees is a
 * legitimate net of zero (the backend allows `@PositiveOrZero`).
 *
 * Zero-exponent currencies (IDR) reject a `.`/`,` outright rather than mis-scale: "45.000" typed
 * as the Indonesian grouping-separator habit for forty-five thousand must never parse as
 * `Number("45.000") === 45` (the E6/E7 W2 lesson, mirrored from features/expenses/amount.ts).
 */
export function parsePlatformAmountInput(
  value: string,
  currency: string,
  options: { allowZero?: boolean } = {},
): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const exponent = isoMinorExponent(currency)
  if (exponent === 0 && /[.,]/.test(trimmed)) return null
  const major = Number(trimmed)
  if (!Number.isFinite(major)) return null
  if (major < 0) return null
  if (!options.allowZero && major === 0) return null
  return Math.round(major * 10 ** exponent)
}

/**
 * The fee is DERIVED (gross − net), mirroring the backend's `buildSettlementEntry` — never a
 * client-entered field. Returns `null` until both amounts have parsed successfully so the form can
 * show a placeholder rather than a stale/wrong figure.
 */
export function deriveFeeMinor(grossMinor: number | null, netMinor: number | null): number | null {
  if (grossMinor == null || netMinor == null) return null
  return grossMinor - netMinor
}
