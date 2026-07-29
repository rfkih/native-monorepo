/**
 * Plain (non-component) tax helpers. Maps the RFC-7807 problems the tax endpoints return to friendly
 * i18n keys; anything else falls back to a generic error key.
 */

import { ApiError } from '@/lib/api'

export function taxErrorKey(
  err: unknown,
):
  | 'tax.errors.noActivity'
  | 'tax.errors.invalidState'
  | 'tax.errors.currencyMismatch'
  | 'tax.errors.generic' {
  if (err instanceof ApiError) {
    const type = err.problem?.type ?? ''
    if (err.status === 400 && type.includes('tax-no-activity')) return 'tax.errors.noActivity'
    if (err.status === 409 && type.includes('tax-invalid-state')) return 'tax.errors.invalidState'
    if (err.status === 422 && type.includes('tax-currency-mismatch')) {
      return 'tax.errors.currencyMismatch'
    }
  }
  return 'tax.errors.generic'
}
