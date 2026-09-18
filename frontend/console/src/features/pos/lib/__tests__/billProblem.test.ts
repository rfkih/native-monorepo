import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api'
import { billProblemKey, billProblemMessage } from '../billProblem'

function fault(type: string, status = 409) {
  return new ApiError(status, { type: `https://errors.nativeapp.id/${type}` }, `HTTP ${status}`)
}

describe('bill problem slugs → localized copy (RFC-7807 type, never the raw detail)', () => {
  it('maps every lockdown / lifecycle slug the bill write endpoints answer with', () => {
    expect(billProblemKey(fault('bill-mutation-forbidden', 403))).toBe('bills.errNeedsManager')
    expect(billProblemKey(fault('bill-has-paid-lines'))).toBe('bills.errHasPaidLines')
    expect(billProblemKey(fault('bill-line-paid'))).toBe('bills.errLinePaid')
    expect(billProblemKey(fault('bill-line-reserved'))).toBe('bills.errLineReserved')
    expect(billProblemKey(fault('bill-not-open'))).toBe('bills.errNotOpen')
  })

  it('an unknown slug, a problem without a type, and a non-ApiError all yield null', () => {
    expect(billProblemKey(fault('bill-not-found', 404))).toBeNull()
    expect(billProblemKey(new ApiError(500, null, 'HTTP 500'))).toBeNull()
    expect(billProblemKey(new Error('offline'))).toBeNull()
    expect(billProblemKey('nope')).toBeNull()
  })

  it('billProblemMessage translates a known slug and falls back to the message otherwise', () => {
    const t = (key: string) => `T(${key})`
    expect(billProblemMessage(t, fault('bill-line-reserved'))).toBe('T(bills.errLineReserved)')
    expect(billProblemMessage(t, new Error('offline'))).toBe('offline')
    expect(billProblemMessage(t, 'plain')).toBe('plain')
  })
})
