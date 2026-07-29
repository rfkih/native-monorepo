/**
 * Plain (non-component) AP helpers — split out of parts.tsx so that file only exports components
 * (keeps react-refresh/only-export-components clean; mirrors why financeUi/money/period live in
 * their own modules rather than beside a component).
 */

import { ApiError } from '@/lib/api'

/** Localized short date, e.g. `Jul 28, 2026` / `28 Jul 2026` — falls back to an em dash. */
export function formatDate(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(d)
}

/**
 * Maps the RFC-7807 409 `bill-invalid-state` problem (bad transition / overpay) to a friendly
 * i18n key; anything else falls back to a generic error key.
 *
 * The backend reports every illegal bill transition — double-post, void-when-paid, AND
 * overpay — under the same `bill-invalid-state` type (see finance-service `ApAdvice` /
 * `Bill.recordPayment`), so overpay is distinguished by its `detail` message ("… exceeds the
 * outstanding balance …"). The UI now validates the amount client-side before submit, so this
 * server-side overpay path is a rare fallback (e.g. a stale outstanding balance in the form).
 */
export function billErrorKey(
  err: unknown,
): 'ap.detail.errors.overpay' | 'ap.detail.errors.invalidState' | 'ap.detail.errors.generic' {
  if (
    err instanceof ApiError &&
    err.status === 409 &&
    err.problem?.type?.includes('bill-invalid-state')
  ) {
    if (err.problem.detail?.includes('exceeds the outstanding balance')) {
      return 'ap.detail.errors.overpay'
    }
    return 'ap.detail.errors.invalidState'
  }
  return 'ap.detail.errors.generic'
}
