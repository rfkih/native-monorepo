import { describe, expect, it } from 'vitest'
import { channelName, isDisplayMessage } from '../displayChannel'

describe('displayChannel — channelName', () => {
  it('namespaces the channel per outlet', () => {
    expect(channelName('outlet-1')).toBe('pos-display:outlet-1')
    expect(channelName('outlet-1')).not.toBe(channelName('outlet-2'))
  })
})

describe('displayChannel — isDisplayMessage', () => {
  it('accepts a well-formed CART_UPDATED message', () => {
    expect(
      isDisplayMessage({
        type: 'CART_UPDATED',
        lines: [{ name: 'Nasi Goreng', qty: 2, unitPriceMinor: 25_000, lineTotalMinor: 50_000 }],
        breakdown: {
          subtotalMinor: 50_000,
          discountMinor: 0,
          serviceChargeMinor: 5_000,
          taxMinor: 5_500,
          grandTotalMinor: 60_500,
          currency: 'IDR',
        },
      }),
    ).toBe(true)
  })

  it('accepts CART_UPDATED with empty lines and a null breakdown', () => {
    expect(isDisplayMessage({ type: 'CART_UPDATED', lines: [], breakdown: null })).toBe(true)
  })

  it('accepts PAYMENT_STARTED, PAYMENT_COMPLETED, and IDLE', () => {
    expect(
      isDisplayMessage({ type: 'PAYMENT_STARTED', due: { amountMinor: 60_500, currency: 'IDR' } }),
    ).toBe(true)
    expect(
      isDisplayMessage({ type: 'PAYMENT_COMPLETED', change: { amountMinor: 0, currency: 'IDR' } }),
    ).toBe(true)
    expect(isDisplayMessage({ type: 'IDLE' })).toBe(true)
  })

  it('rejects non-objects, unknown types, and missing fields', () => {
    expect(isDisplayMessage(null)).toBe(false)
    expect(isDisplayMessage(undefined)).toBe(false)
    expect(isDisplayMessage('CART_UPDATED')).toBe(false)
    expect(isDisplayMessage({})).toBe(false)
    expect(isDisplayMessage({ type: 'SOMETHING_ELSE' })).toBe(false)
    expect(isDisplayMessage({ type: 'PAYMENT_STARTED' })).toBe(false)
  })

  it('rejects a CART_UPDATED message with malformed lines or breakdown', () => {
    expect(isDisplayMessage({ type: 'CART_UPDATED', lines: 'nope', breakdown: null })).toBe(false)
    expect(
      isDisplayMessage({
        type: 'CART_UPDATED',
        lines: [{ name: 'x', qty: '2', unitPriceMinor: 1, lineTotalMinor: 1 }],
        breakdown: null,
      }),
    ).toBe(false)
    expect(
      isDisplayMessage({
        type: 'CART_UPDATED',
        lines: [],
        breakdown: { subtotalMinor: 1 }, // missing every other required field
      }),
    ).toBe(false)
  })

  it('rejects PAYMENT_STARTED/PAYMENT_COMPLETED with a non-numeric amount', () => {
    expect(
      isDisplayMessage({ type: 'PAYMENT_STARTED', due: { amountMinor: '100', currency: 'IDR' } }),
    ).toBe(false)
    expect(
      isDisplayMessage({ type: 'PAYMENT_COMPLETED', change: { amountMinor: 0, currency: 7 } }),
    ).toBe(false)
  })
})
