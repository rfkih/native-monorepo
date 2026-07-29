/** Plain (non-component) asset helpers: maps RFC-7807 problems to friendly i18n keys. */

import { ApiError } from '@/lib/api'

export function assetErrorKey(
  err: unknown,
): 'assets.errors.invalid' | 'assets.errors.currencyMismatch' | 'assets.errors.generic' {
  if (err instanceof ApiError) {
    const type = err.problem?.type ?? ''
    if (err.status === 422 && type.includes('asset-currency-mismatch')) {
      return 'assets.errors.currencyMismatch'
    }
    if (err.status === 400) return 'assets.errors.invalid'
  }
  return 'assets.errors.generic'
}
