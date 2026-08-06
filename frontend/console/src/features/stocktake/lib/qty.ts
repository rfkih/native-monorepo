/**
 * qty.ts — plain (non-money) quantity input parsing + display for the stocktake feature (ADR 0038
 * phase 3). A counted quantity is a bare non-negative integer, never a currency amount, so it does
 * NOT go through the money exponent scaling in lib/money.ts (parseDiscountInput) — but per rule 9 it
 * still renders via Intl, never a raw `toString()`.
 */

/**
 * Parses a counted-quantity input. Returns `null` for blank OR invalid (non-integer, negative,
 * NaN) input — unlike the money parsers, an invalid quantity is NOT silently coerced to 0, because
 * a stocktake line with a wrong count would misstate shrinkage; callers block submission instead.
 */
export function parseQtyInput(raw: string): number | null {
  if (raw.trim() === '') return null
  const val = Number(raw)
  if (!Number.isFinite(val) || !Number.isInteger(val) || val < 0) return null
  return val
}

/** Locale-aware integer formatting for a stock quantity or variance count. */
export function formatQty(qty: number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(qty)
}

/** Signed variant for a variance ("+3" / "-2" / "0") — Intl's signDisplay does the sign, never manual string concatenation. */
export function formatSignedQty(qty: number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 0, signDisplay: 'exceptZero' }).format(qty)
}
