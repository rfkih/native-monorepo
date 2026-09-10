import { toDisplayQty, type UnitBearing } from './lib/units'

/**
 * Plain (non-component) inventory formatters — split out so the screen files only export
 * components (keeps react-refresh/only-export-components clean; mirrors why ap/format.ts,
 * bank/format.ts, and tax/format.ts each keep their own small formatter beside their feature).
 */

/**
 * A per-day usage RATE in the shown unit. Unlike a stock figure a rate is fractional whatever the
 * unit — 0,4 pcs a day is a real number, and `formatShownQty`'s whole-unit rounding would print it
 * as "0 pcs" beside a finite "12 days left".
 */
export function formatRateQty(rateBase: number, ing: UnitBearing, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(
    toDisplayQty(rateBase, ing),
  )
}

/** Days left, to one decimal under ten and whole above — "3,9" reads, "247,3" is noise. */
export function formatDays(days: number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: days < 10 ? 1 : 0 }).format(days)
}

/** Localized date + time, e.g. `Aug 17, 2026, 3:04 PM` / `17 Agu 2026 15.04` — for the
 *  `activatedAt` server instant (a moment, not a plain calendar date). Falls back to an em dash. */
export function formatActivatedAt(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}
