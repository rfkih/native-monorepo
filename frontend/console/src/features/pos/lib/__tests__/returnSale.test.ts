import { describe, it, expect } from 'vitest'
import { ApiError } from '@/lib/api'
import {
  canReturnPayment,
  netSaleAmountMinor,
  refundErrorKey,
  reversalStatusKey,
} from '../returnSale'

describe('canReturnPayment', () => {
  it('allows a CAPTURED payment to be returned', () => {
    expect(canReturnPayment({ status: 'CAPTURED' })).toBe(true)
  })

  it('refuses a payment that is not settled or was already reversed', () => {
    for (const status of [
      'PENDING',
      'VOIDED',
      'REFUNDED',
      'PARTIALLY_REFUNDED',
      'ABANDONED',
      'FAILED',
    ]) {
      expect(canReturnPayment({ status })).toBe(false)
    }
  })
})

describe('refundErrorKey', () => {
  const err = (status: number) => new ApiError(status, null, `HTTP ${status}`)

  it('maps 403 to the owner/manager-only message', () => {
    expect(refundErrorKey(err(403))).toBe('pos.return.errorForbidden')
  })

  it('maps the partial/gift-card/already-refunded rejections (400/409/422) to errorRejected', () => {
    expect(refundErrorKey(err(400))).toBe('pos.return.errorRejected')
    expect(refundErrorKey(err(409))).toBe('pos.return.errorRejected')
    expect(refundErrorKey(err(422))).toBe('pos.return.errorRejected')
  })

  it('falls back to a generic message for other server errors', () => {
    expect(refundErrorKey(err(500))).toBe('pos.return.errorGeneric')
  })

  it('falls back to a generic message for a non-ApiError (network/unknown)', () => {
    expect(refundErrorKey(new Error('network down'))).toBe('pos.return.errorGeneric')
    expect(refundErrorKey(null)).toBe('pos.return.errorGeneric')
  })
})

describe('reversalStatusKey', () => {
  it('maps each reversal status to its badge i18n key', () => {
    expect(reversalStatusKey('VOIDED')).toBe('pos.receipt.statusVoided')
    expect(reversalStatusKey('REFUNDED')).toBe('pos.receipt.statusRefunded')
    expect(reversalStatusKey('PARTIALLY_REFUNDED')).toBe('pos.receipt.statusPartiallyRefunded')
  })

  it('returns null for a non-reversal status or an absent value', () => {
    expect(reversalStatusKey('CAPTURED')).toBeNull()
    expect(reversalStatusKey('PENDING')).toBeNull()
    expect(reversalStatusKey(null)).toBeNull()
    expect(reversalStatusKey(undefined)).toBeNull()
  })
})

describe('netSaleAmountMinor', () => {
  it('counts a plain captured sale in full', () => {
    expect(
      netSaleAmountMinor({ amountMinor: 50_000, paymentStatus: 'CAPTURED', refundedMinor: 0 }),
    ).toBe(50_000)
  })

  it('zeroes a VOIDED sale even though its refundedMinor stays 0 (a void is not a refund)', () => {
    expect(
      netSaleAmountMinor({ amountMinor: 50_000, paymentStatus: 'VOIDED', refundedMinor: 0 }),
    ).toBe(0)
  })

  it('zeroes a fully REFUNDED sale via its cumulative refund', () => {
    expect(
      netSaleAmountMinor({ amountMinor: 50_000, paymentStatus: 'REFUNDED', refundedMinor: 50_000 }),
    ).toBe(0)
  })

  it('leaves a partial refund its remainder', () => {
    expect(
      netSaleAmountMinor({
        amountMinor: 50_000,
        paymentStatus: 'PARTIALLY_REFUNDED',
        refundedMinor: 20_000,
      }),
    ).toBe(30_000)
  })

  it('nets the full amount for a stale-cache or legacy row without the fields', () => {
    expect(netSaleAmountMinor({ amountMinor: 50_000 })).toBe(50_000)
    expect(netSaleAmountMinor({ amountMinor: 50_000, paymentStatus: null })).toBe(50_000)
  })
})
