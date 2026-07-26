/**
 * Money rendering — rule 8: the wire carries integer MINOR units + an ISO-4217 currency, never a
 * float. We convert minor→major using the currency's ISO-4217 minor-unit exponent (matching the
 * backend's libs/money / java.util.Currency, NOT the CLDR display digits, which differ for IDR),
 * then format locale-aware via Intl.NumberFormat (rule 9 — no server-side localized strings).
 */

// ISO-4217 minor-unit exponents — MUST match the backend libs/money Money.fractionDigits().
// CRITICAL: Native books IDR with ZERO minor digits (whole rupiah), overriding the JDK/CLDR ISO
// table which says 2 for IDR — see Money.java `case "IDR" -> 0`. amount_minor on the wire is scaled
// by this exponent, so a mismatch mis-scales every displayed AND recorded amount by a power of ten.
// USD and the rest below use the JDK default, which the backend does not override. Default 2.
const ISO_MINOR_EXPONENT: Record<string, number> = {
  IDR: 0,
  USD: 2,
  EUR: 2,
  SGD: 2,
  JPY: 0,
  KRW: 0,
  VND: 0,
  BHD: 3,
  KWD: 3,
}

export function isoMinorExponent(currency: string): number {
  return ISO_MINOR_EXPONENT[currency] ?? 2
}

export function minorToMajor(minor: number, currency: string): number {
  return minor / 10 ** isoMinorExponent(currency)
}

/** Locale-aware currency string, e.g. `Rp 15.000` / `$15,000.00`. */
export function formatMoney(minor: number, currency: string, locale: string): string {
  const major = minorToMajor(minor, currency)
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(major)
  } catch {
    return `${major.toLocaleString(locale)} ${currency}`
  }
}

/** Just the grouped number (no currency symbol) — for tight tabular columns with a separate code. */
export function formatAmount(minor: number, currency: string, locale: string): string {
  const digits = isoDisplayDigits(currency)
  return minorToMajor(minor, currency).toLocaleString(locale, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

/** Locale-aware percent with one fraction digit — the single formatter every finance surface uses. */
export function formatPercent(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, {
    style: 'percent',
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).format(value)
}

function isoDisplayDigits(currency: string): number {
  try {
    return (
      new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
        .maximumFractionDigits ?? 2
    )
  } catch {
    return 2
  }
}
