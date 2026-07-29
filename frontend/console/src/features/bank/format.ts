/**
 * Plain (non-component) Bank helpers — split out of parts.tsx so that file only exports
 * components (keeps react-refresh/only-export-components clean; mirrors ar/format.ts and
 * ap/format.ts).
 */

import { ApiError } from '@/lib/api'
import { isoMinorExponent, minorToMajor } from '@/lib/money'
import type { ReconcileCategory } from './api'

/** Localized short date, e.g. `Jul 28, 2026` / `28 Jul 2026` — falls back to an em dash. */
export function formatDate(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(d)
}

/**
 * Money with an explicit sign, e.g. `+Rp 15.000` / `-$150.00` — deposits vs withdrawals must
 * never be distinguished by color alone (a11y floor), so the sign is always printed; the caller
 * additionally colors the figure (profit tone for +, loss tone for −) as a secondary cue.
 */
export function formatSignedMoney(minor: number, currency: string, locale: string): string {
  const major = minorToMajor(minor, currency)
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      signDisplay: 'exceptZero',
    }).format(major)
  } catch {
    const sign = major > 0 ? '+' : ''
    return `${sign}${major.toLocaleString(locale)} ${currency}`
  }
}

/**
 * Major (possibly signed, e.g. "-15000" or "12.50") → signed integer minor units, using the
 * currency's ISO-4217 minor-unit exponent (rule 8 — never a float on the wire). Returns null for
 * anything that isn't a finite, non-zero number — a statement line with no amount is meaningless.
 */
export function majorToSignedMinor(major: string, currency: string): number | null {
  const parsed = Number(major)
  if (!Number.isFinite(parsed) || parsed === 0) return null
  return Math.round(parsed * 10 ** isoMinorExponent(currency))
}

/** A line's direction is its sign — only these categories are valid for a POST /reconcile call. */
export function categoriesFor(amountMinor: number): ReconcileCategory[] {
  return amountMinor < 0 ? ['CLEARING', 'BANK_FEE'] : ['CLEARING', 'INTEREST']
}

/**
 * Maps the reconcile endpoint's known failures to a friendly i18n key:
 *  - 409 (RFC-7807 `https://errors.nativeapp.id/...`) — the line was already reconciled (a
 *    one-shot state transition re-attempted, e.g. from a stale list after another tab reconciled
 *    it first).
 *  - 400 — the chosen category doesn't match the line's direction (shouldn't happen through this
 *    UI, which only ever offers the valid pair, but the mapping stays defensive).
 */
export function reconcileErrorKey(
  err: unknown,
): 'bank.reconcile.errors.alreadyReconciled' | 'bank.reconcile.errors.invalidCategory' | 'bank.reconcile.errors.generic' {
  if (err instanceof ApiError) {
    if (err.status === 409) return 'bank.reconcile.errors.alreadyReconciled'
    if (err.status === 400) return 'bank.reconcile.errors.invalidCategory'
  }
  return 'bank.reconcile.errors.generic'
}
