/**
 * Plain (non-component) opening-balances helpers: maps the RFC-7807 problems raised by
 * `POST /api/v1/opening-balances` (`OpeningBalanceAdvice`, ADR 0037) to friendly i18n keys — the
 * features/assets/format.ts `assetErrorKey` idiom. Errors from `POST /api/v1/assets/opening` reuse
 * `assetErrorKey` directly in OpeningBalances.tsx (that endpoint is handled by the SAME `AssetAdvice`
 * as `POST /api/v1/assets`), so they are not duplicated here.
 */

import { ApiError } from '@/lib/api'

export function openingBalanceErrorKey(
  err: unknown,
):
  | 'openingBalances.errors.alreadyRecorded'
  | 'openingBalances.errors.keyConflict'
  | 'openingBalances.errors.pnlAccount'
  | 'openingBalances.errors.sealedPeriod'
  | 'openingBalances.errors.currencyMismatch'
  | 'openingBalances.errors.invalidRequest'
  | 'openingBalances.errors.generic' {
  if (err instanceof ApiError) {
    const type = err.problem?.type ?? ''
    if (err.status === 409 && type.includes('opening-balances-already-recorded')) {
      return 'openingBalances.errors.alreadyRecorded'
    }
    if (err.status === 409 && type.includes('opening-balance-idempotency-key-conflict')) {
      return 'openingBalances.errors.keyConflict'
    }
    if (err.status === 422 && type.includes('opening-balance-pnl-account')) {
      return 'openingBalances.errors.pnlAccount'
    }
    if (err.status === 422 && type.includes('opening-balance-sealed-period')) {
      return 'openingBalances.errors.sealedPeriod'
    }
    if (err.status === 422 && type.includes('opening-balance-currency-mismatch')) {
      return 'openingBalances.errors.currencyMismatch'
    }
    if (err.status === 400) return 'openingBalances.errors.invalidRequest'
  }
  return 'openingBalances.errors.generic'
}
