/**
 * money.ts — Intl-based currency formatting (rule 8: money is integer minor units + an ISO-4217
 * code, formatted only via Intl, never by hand). Duplicated in miniature from the console's
 * src/lib/money.ts since this is a separate deployable with no shared package (see main.tsx).
 */

// ISO-4217 minor-unit exponents — MUST match the backend's Money type (libs/money). CRITICAL:
// Native books IDR with ZERO minor digits (whole rupiah), overriding the JDK/CLDR default of 2.
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

function isoMinorExponent(currency: string): number {
  return ISO_MINOR_EXPONENT[currency] ?? 2
}

/** Locale-aware currency string, e.g. `Rp 15.000` / `$15,000.00`. */
export function formatMoney(minor: number, currency: string, locale: string): string {
  const major = minor / 10 ** isoMinorExponent(currency)
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(major)
  } catch {
    return `${major.toLocaleString(locale)} ${currency}`
  }
}
