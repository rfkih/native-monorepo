/** Accounting-period helpers — periods are `YYYY-MM` strings (matching the finance API). */

export function currentPeriod(): string {
  const now = new Date()
  return `${now.getUTCFullYear()}-${pad(now.getUTCMonth() + 1)}`
}

export function shiftPeriod(period: string, deltaMonths: number): string {
  const [y, m] = period.split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 + deltaMonths, 1))
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`
}

/** Localized label, e.g. `June 2026` / `Juni 2026`. */
export function formatPeriod(period: string, locale: string): string {
  const [y, m] = period.split('-').map(Number)
  if (!y || !m) return period
  const d = new Date(Date.UTC(y, m - 1, 1))
  return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
    d,
  )
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}
