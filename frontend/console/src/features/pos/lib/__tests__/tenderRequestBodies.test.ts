import { describe, it, expect } from 'vitest'
import {
  checkoutRequestBody,
  payParkedRequestBody,
  payBillRequestBody,
} from '../tenderRequestBodies'

/**
 * Contract lock (ADR 0036 Phase B2): the server reads `channelCode` from the request's TOP
 * LEVEL — a code left only inside the nested payment block is silently ignored and every
 * ONLINE checkout 400s ("channelCode is required when tenderType is ONLINE"). These tests pin
 * the mirror on all three tender-carrying bodies.
 */

const BUSINESS = '642c4a5b-794d-43e3-99b5-996d3346f567'
const LINES = [{ menuItemId: 'm1', qty: 1 }] as never

describe('checkoutRequestBody', () => {
  it('mirrors an ONLINE payment.channelCode to the top level', () => {
    const body = checkoutRequestBody(BUSINESS, {
      idempotencyKey: 'k1',
      lines: LINES,
      payment: { tenderType: 'ONLINE', channelCode: 'SHOPEE' },
    })
    expect(body.channelCode).toBe('SHOPEE')
    expect(body.payment).toEqual({ tenderType: 'ONLINE', channelCode: 'SHOPEE' })
    expect(body.businessId).toBe(BUSINESS)
  })

  it('sends a null top-level channelCode for a non-ONLINE tender and for no payment', () => {
    expect(
      checkoutRequestBody(BUSINESS, {
        idempotencyKey: 'k2',
        lines: LINES,
        payment: { tenderType: 'CASH', tenderedMinor: 50_000 },
      }).channelCode,
    ).toBeNull()
    expect(
      checkoutRequestBody(BUSINESS, { idempotencyKey: 'k3', lines: LINES }).channelCode,
    ).toBeNull()
  })
})

describe('payParkedRequestBody', () => {
  it('mirrors an ONLINE payment.channelCode to the top level', () => {
    const body = payParkedRequestBody({
      orderId: 'o1',
      payment: { tenderType: 'ONLINE', channelCode: 'GOFOOD' },
    })
    expect(body.channelCode).toBe('GOFOOD')
    expect(body.payment).toEqual({ tenderType: 'ONLINE', channelCode: 'GOFOOD' })
  })

  it('sends null for a cash pay', () => {
    const body = payParkedRequestBody({
      orderId: 'o2',
      payment: { tenderType: 'CASH', tenderedMinor: 20_000 },
    })
    expect(body.channelCode).toBeNull()
  })
})

describe('payBillRequestBody', () => {
  it('mirrors an ONLINE payment.channelCode to the top level', () => {
    const body = payBillRequestBody({
      billId: 'b1',
      payment: { tenderType: 'ONLINE', channelCode: 'GRABFOOD' },
      idempotencyKey: 'k4',
    })
    expect(body.channelCode).toBe('GRABFOOD')
    expect(body.payment).toEqual({ tenderType: 'ONLINE', channelCode: 'GRABFOOD' })
    expect(body.idempotencyKey).toBe('k4')
  })

  it('sends null for a cash settle and preserves the lineIds subset', () => {
    const body = payBillRequestBody({
      billId: 'b2',
      payment: { tenderType: 'CASH', tenderedMinor: 75_000 },
      lineIds: ['l1', 'l2'],
    })
    expect(body.channelCode).toBeNull()
    expect(body.lineIds).toEqual(['l1', 'l2'])
  })
})

describe('falsy-vs-null coercions and URL-only fields', () => {
  it('coerces falsy optionals to null exactly as the old inline bodies did', () => {
    const body = checkoutRequestBody(BUSINESS, {
      idempotencyKey: 'k5',
      lines: LINES,
      discountMinor: 0,
      couponCode: '',
      loyaltyRedeemPoints: 0,
      giftCardRedeemMinor: 0,
    })
    expect(body.discountMinor).toBeNull()
    expect(body.couponCode).toBeNull()
    expect(body.loyaltyRedeemPoints).toBeNull()
    expect(body.giftCardRedeemMinor).toBeNull()
    expect(payBillRequestBody({ billId: 'b3', discountMinor: 0, lineIds: [] })).toMatchObject({
      discountMinor: null,
      lineIds: null,
      idempotencyKey: null,
    })
  })

  it('keeps the URL-only ids (orderId/billId) out of the request body', () => {
    expect('orderId' in payParkedRequestBody({ orderId: 'o3' })).toBe(false)
    expect('billId' in payBillRequestBody({ billId: 'b4' })).toBe(false)
  })
})
