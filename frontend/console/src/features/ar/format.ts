/**
 * Plain (non-component) AR helpers — split out of parts.tsx so that file only exports components
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
 * Maps the RFC-7807 409 `invoice-invalid-state` problem (bad transition / overpay) to a friendly
 * i18n key; anything else falls back to a generic error key.
 *
 * The backend reports every illegal invoice transition — double-issue, void-when-paid, AND
 * overpay — under the same `invoice-invalid-state` type (see finance-service `ArAdvice` /
 * `Invoice.recordPayment`), so overpay is distinguished by its `detail` message ("… exceeds the
 * outstanding balance …"). The UI now validates the amount client-side before submit, so this
 * server-side overpay path is a rare fallback (e.g. a stale outstanding balance in the form).
 */
export function invoiceErrorKey(
  err: unknown,
): 'ar.detail.errors.overpay' | 'ar.detail.errors.invalidState' | 'ar.detail.errors.generic' {
  if (
    err instanceof ApiError &&
    err.status === 409 &&
    err.problem?.type?.includes('invoice-invalid-state')
  ) {
    if (err.problem.detail?.includes('exceeds the outstanding balance')) {
      return 'ar.detail.errors.overpay'
    }
    return 'ar.detail.errors.invalidState'
  }
  return 'ar.detail.errors.generic'
}
