import { describe, it, expect } from 'vitest'
import { ApiError } from '@/lib/api'
import { canReturnPayment, refundErrorKey } from '../returnSale'

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
