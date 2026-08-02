/**
 * errorKeys.test.ts — pins the checkout-error → i18n-key mapping shared by every payment
 * surface (redesign P1). The problem+json type URIs are backend contracts (stable
 * errors.nativeapp.id URIs); the mapping must survive the payment-surface consolidation intact.
 */
import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api'
import { checkoutErrorKey, couponCheckoutErrorKey, parseInsufficientStock } from '../errorKeys'

function apiError(status: number, type: string, extra: Record<string, unknown> = {}): ApiError {
  return new ApiError(status, { type, ...extra }, `HTTP ${status}`)
}

describe('checkoutErrorKey', () => {
  it('maps 403 outlet-not-assigned', () => {
    expect(checkoutErrorKey(apiError(403, 'https://errors.nativeapp.id/outlet-not-assigned'))).toBe(
      'pos.payment.outletNotAssigned',
    )
  })

  it('maps 403 module-not-entitled (service verticals, ADR 0024)', () => {
    expect(checkoutErrorKey(apiError(403, 'https://errors.nativeapp.id/module-not-entitled'))).toBe(
      'servicePos.errors.moduleNotEntitled',
    )
  })

  it('maps the checkout-time coupon rejections (ADR 0026: 422 invalid / 409 exhausted)', () => {
    expect(checkoutErrorKey(apiError(422, 'https://errors.nativeapp.id/coupon-invalid'))).toBe(
      'pos.coupon.invalid',
    )
    expect(checkoutErrorKey(apiError(409, 'https://errors.nativeapp.id/coupon-exhausted'))).toBe(
      'pos.coupon.exhausted',
    )
  })

  it('maps the ADR 0027 loyalty/gift-card redemption faults (both 409)', () => {
    expect(
      checkoutErrorKey(apiError(409, 'https://errors.nativeapp.id/loyalty-balance-insufficient')),
    ).toBe('pos.loyalty.member.insufficientBalance')
    expect(checkoutErrorKey(apiError(409, 'https://errors.nativeapp.id/gift-card-unusable'))).toBe(
      'pos.loyalty.giftCard.unusableAtCheckout',
    )
  })

  it('returns null for unknown errors (callers fall back to a generic message)', () => {
    expect(checkoutErrorKey(apiError(500, 'https://errors.nativeapp.id/whatever'))).toBeNull()
    expect(checkoutErrorKey(new Error('network'))).toBeNull()
    expect(checkoutErrorKey(undefined)).toBeNull()
  })

  it('a coupon type on the WRONG status is not a coupon rejection', () => {
    expect(couponCheckoutErrorKey(apiError(409, 'coupon-invalid'))).toBeNull()
    expect(couponCheckoutErrorKey(apiError(422, 'coupon-exhausted'))).toBeNull()
  })
})

describe('parseInsufficientStock', () => {
  it('parses the 422 insufficient-stock problem into interpolation params', () => {
    expect(
      parseInsufficientStock(
        apiError(422, 'https://errors.nativeapp.id/insufficient-stock', {
          itemName: 'Es Teh Manis',
          available: 2,
        }),
      ),
    ).toEqual({ itemName: 'Es Teh Manis', available: 2 })
  })

  it('returns null when the interpolation fields are missing or mistyped', () => {
    expect(
      parseInsufficientStock(apiError(422, 'https://errors.nativeapp.id/insufficient-stock')),
    ).toBeNull()
    expect(
      parseInsufficientStock(
        apiError(422, 'https://errors.nativeapp.id/insufficient-stock', {
          itemName: 'X',
          available: 'two',
        }),
      ),
    ).toBeNull()
  })

  it('returns null for other statuses and non-ApiErrors', () => {
    expect(
      parseInsufficientStock(apiError(409, 'https://errors.nativeapp.id/insufficient-stock')),
    ).toBeNull()
    expect(parseInsufficientStock(new Error('x'))).toBeNull()
  })
})
