/**
 * discountInput.ts — manual-discount input parsing, extracted from Pos.tsx / ServicePos.tsx
 * (redesign P1 — the two copies were already identical).
 *
 * The input is a MAJOR-unit human entry ("15000" rupiah, "12.50" dollars); the return value is
 * minor units via the ISO-4217 exponent (IDR = 0, so whole rupiah pass through unscaled — rule 8).
 * Empty/invalid/negative input parses to 0 (no discount), never an error.
 */
import { isoMinorExponent } from '@/lib/money'

export function parseDiscountInput(input: string, currency: string): number {
  if (!input || input.trim() === '') return 0
  const major = Number(input)
  if (isNaN(major) || major < 0) return 0
  const exp = isoMinorExponent(currency)
  return Math.round(major * 10 ** exp)
}
