import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api'
import { openingBalanceErrorKey } from '../format'

function apiError(status: number, type: string): ApiError {
  return new ApiError(status, { type }, `HTTP ${status}`)
}

describe('openingBalanceErrorKey', () => {
  it('maps 409 opening-balances-already-recorded', () => {
    expect(
      openingBalanceErrorKey(
        apiError(409, 'https://errors.nativeapp.id/opening-balances-already-recorded'),
      ),
    ).toBe('openingBalances.errors.alreadyRecorded')
  })

  it('maps 409 opening-balance-idempotency-key-conflict', () => {
    expect(
      openingBalanceErrorKey(
        apiError(409, 'https://errors.nativeapp.id/opening-balance-idempotency-key-conflict'),
      ),
    ).toBe('openingBalances.errors.keyConflict')
  })

  it('maps 422 opening-balance-pnl-account', () => {
    expect(
      openingBalanceErrorKey(apiError(422, 'https://errors.nativeapp.id/opening-balance-pnl-account')),
    ).toBe('openingBalances.errors.pnlAccount')
  })

  it('maps 422 opening-balance-sealed-period', () => {
    expect(
      openingBalanceErrorKey(
        apiError(422, 'https://errors.nativeapp.id/opening-balance-sealed-period'),
      ),
    ).toBe('openingBalances.errors.sealedPeriod')
  })

  it('maps 422 opening-balance-currency-mismatch', () => {
    expect(
      openingBalanceErrorKey(
        apiError(422, 'https://errors.nativeapp.id/opening-balance-currency-mismatch'),
      ),
    ).toBe('openingBalances.errors.currencyMismatch')
  })

  it('maps every other 400 to the generic invalid-request key', () => {
    expect(
      openingBalanceErrorKey(apiError(400, 'https://errors.nativeapp.id/opening-balance-invalid-request')),
    ).toBe('openingBalances.errors.invalidRequest')
  })

  it('falls back to generic for unknown errors', () => {
    expect(openingBalanceErrorKey(apiError(500, 'https://errors.nativeapp.id/whatever'))).toBe(
      'openingBalances.errors.generic',
    )
    expect(openingBalanceErrorKey(new Error('network'))).toBe('openingBalances.errors.generic')
    expect(openingBalanceErrorKey(undefined)).toBe('openingBalances.errors.generic')
  })
})
