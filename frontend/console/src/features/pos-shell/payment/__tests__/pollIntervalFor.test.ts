import { describe, expect, it } from 'vitest'
import { isTerminalPaymentStatus, pollIntervalFor } from '../pollIntervalFor'

describe('isTerminalPaymentStatus', () => {
  it('PENDING is not terminal — keep polling', () => {
    expect(isTerminalPaymentStatus('PENDING')).toBe(false)
  })

  it('null/undefined (no data yet) is not terminal — keep polling', () => {
    expect(isTerminalPaymentStatus(null)).toBe(false)
    expect(isTerminalPaymentStatus(undefined)).toBe(false)
  })

  it('CAPTURED and every other non-PENDING status is terminal', () => {
    expect(isTerminalPaymentStatus('CAPTURED')).toBe(true)
    expect(isTerminalPaymentStatus('VOIDED')).toBe(true)
    expect(isTerminalPaymentStatus('ABANDONED')).toBe(true)
    expect(isTerminalPaymentStatus('FAILED')).toBe(true)
  })
})

describe('pollIntervalFor', () => {
  it('polls every 3s while healthy and pending', () => {
    expect(pollIntervalFor('PENDING', false)).toBe(3_000)
    expect(pollIntervalFor(null, false)).toBe(3_000)
  })

  it('backs off to 8s after a fetch error, while still pending', () => {
    expect(pollIntervalFor('PENDING', true)).toBe(8_000)
    expect(pollIntervalFor(undefined, true)).toBe(8_000)
  })

  it('stops polling once the status is terminal, error or not', () => {
    expect(pollIntervalFor('CAPTURED', false)).toBe(false)
    expect(pollIntervalFor('CAPTURED', true)).toBe(false)
  })
})
