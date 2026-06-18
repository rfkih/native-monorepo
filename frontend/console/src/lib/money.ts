/**
 * Money rendering — rule 8: the wire carries integer MINOR units + an ISO-4217 currency, never a
 * float. We convert minor→major using the currency's ISO-4217 minor-unit exponent (matching the
 * backend's libs/money / java.util.Currency, NOT the CLDR display digits, which differ for IDR),
 * then format locale-aware via Intl.NumberFormat (rule 9 — no server-side localized strings).
 */

// ISO-4217 minor-unit exponents (java.util.Currency.getDefaultFractionDigits). Default 2.
// IDR and USD (the currencies supported today) are both 2; common exceptions listed for the future.
const ISO_MINOR_EXPONENT: Record<string, number> = {
  IDR: 2,
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
