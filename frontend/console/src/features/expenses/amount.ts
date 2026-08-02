/**
 * Pure helpers for the expense-claim amount input and the receipt photo picker (ADR 0030, Phase
 * E6) — no React, no network, unit-tested directly (mirrors features/pos/offline/provisionalPricing
 * and features/ar/NewInvoice's inline `Math.round(major * 10 ** exponent)` idiom, lifted out here
 * so it is independently testable).
 */

import { isoMinorExponent, minorToMajor } from '@/lib/money'

/**
 * Parses a user-typed amount (major units, e.g. "45.000" typed as "45000" for IDR, or "12.50" for
 * USD) into integer minor units for the given currency (rule 8 — money on the wire is always
 * integer minor units). Returns `null` for anything that is not a finite, strictly positive number
 * (blank input, "abc", "0", "-5", etc.) — the caller treats `null` as "not ready to submit", never
 * as zero.
 */
export function parseAmountInputToMinor(value: string, currency: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const major = Number(trimmed)
  if (!Number.isFinite(major) || major <= 0) return null
  return Math.round(major * 10 ** isoMinorExponent(currency))
}

/** The inverse of {@link parseAmountInputToMinor} — pre-fills an amount input from a stored claim
 *  (edit-draft mode). Renders a plain decimal (no grouping/currency symbol — this is an editable
 *  input, not a display string; use `lib/money`'s `formatMoney`/`formatAmount` for read-only display). */
export function minorToAmountInputValue(minor: number, currency: string): string {
  const major = minorToMajor(minor, currency)
  const digits = isoMinorExponent(currency)
  return major.toFixed(digits)
}

// ---------------------------------------------------------------------------
// Receipt photo picker — client-side pre-flight checks (ADR 0030 §8)
// ---------------------------------------------------------------------------

/** Mirrors the server whitelist (employee-service `ReceiptContentTypeValidator`) — the client
 *  check is a fast, friendly pre-flight; the server re-validates by MAGIC BYTES regardless, so a
 *  spoofed/renamed file is still rejected there even if it slips past this check. */
export const RECEIPT_ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const

/** Mirrors the server cap (`ExpenseReceipt.MAX_BYTES`, 5 MiB). */
export const RECEIPT_MAX_BYTES = 5 * 1024 * 1024

export type ReceiptFileRejection = 'size' | 'type'

/**
 * Client-side pre-flight for a picked receipt photo: size and declared MIME type. This is a UX
 * courtesy only (immediate feedback before a 5 MiB round trip) — the server is the authority
 * (magic-byte detection + its own size cap), so a permissive result here is never itself a
 * security boundary.
 */
export function validateReceiptFile(file: {
  size: number
  type: string
}): { ok: true } | { ok: false; reason: ReceiptFileRejection } {
  if (file.size <= 0 || file.size > RECEIPT_MAX_BYTES) {
    return { ok: false, reason: 'size' }
  }
  if (!RECEIPT_ALLOWED_TYPES.includes(file.type as (typeof RECEIPT_ALLOWED_TYPES)[number])) {
    return { ok: false, reason: 'type' }
  }
  return { ok: true }
}
