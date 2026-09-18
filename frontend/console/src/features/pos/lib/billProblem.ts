/**
 * RFC-7807 bill problems → i18n keys. The bill write endpoints answer the open-bill lockdown and
 * lifecycle refusals with STABLE `type` slugs (restaurant-service `BillExceptionHandler`); the
 * operator reads localized copy for each, never the server's raw English `detail`
 * (ENGINEERING-STANDARDS §1.2, rule 9). One table, shared by the bill sheet and the order switcher.
 */
import { ApiError } from '@/lib/api'

const BILL_PROBLEM_KEY: Record<string, string> = {
  'bill-mutation-forbidden': 'bills.errNeedsManager',
  'bill-has-paid-lines': 'bills.errHasPaidLines',
  'bill-line-paid': 'bills.errLinePaid',
  // A PENDING gateway (QRIS) payment still reserves lines — cancel waits for it to settle or lapse.
  'bill-line-reserved': 'bills.errLineReserved',
  // Someone else paid or cancelled it since this screen last loaded.
  'bill-not-open': 'bills.errNotOpen',
}

/** The i18n key for a known bill problem slug, or null when the error is not one. */
export function billProblemKey(err: unknown): string | null {
  if (err instanceof ApiError && typeof err.problem?.type === 'string') {
    const type = err.problem.type
    for (const slug of Object.keys(BILL_PROBLEM_KEY)) {
      if (type.includes(slug)) return BILL_PROBLEM_KEY[slug]
    }
  }
  return null
}

/** Localized copy for a known slug; anything else falls back to the error's own message. */
export function billProblemMessage(t: (key: string) => string, err: unknown): string {
  const key = billProblemKey(err)
  if (key) return t(key)
  return err instanceof Error ? err.message : String(err)
}
