import { describe, expect, it } from 'vitest'
import type { RegisterSessionResponse } from '../../registerApi'
import { lastClosedSession, minorToMajorInput } from '../registerFloat'

function row(partial: Partial<RegisterSessionResponse>): RegisterSessionResponse {
  return {
    id: 'id',
    businessId: 'biz',
    status: 'CLOSED',
    businessDate: '2026-08-07',
    openedAt: '2026-08-07T01:00:00Z',
    openingFloatMinor: 0,
    currency: 'IDR',
    closedAt: '2026-08-07T10:00:00Z',
    cashSalesMinor: 0,
    cashRefundsMinor: 0,
    expectedCashMinor: 0,
    countedCashMinor: 0,
    overShortMinor: 0,
    ...partial,
  }
}

describe('lastClosedSession', () => {
  it('returns null for an empty or missing history', () => {
    expect(lastClosedSession(undefined)).toBeNull()
    expect(lastClosedSession([])).toBeNull()
  })

  it('skips an OPEN head row and picks the newest CLOSED one (server order is newest-first)', () => {
    const open = row({ id: 'open', status: 'OPEN', countedCashMinor: null, closedAt: null })
    const yesterday = row({ id: 'y', countedCashMinor: 192_000 })
    const older = row({ id: 'o', countedCashMinor: 50_000 })
    expect(lastClosedSession([open, yesterday, older])?.id).toBe('y')
  })

  it('skips a CLOSED row without a recorded count', () => {
    const uncounted = row({ id: 'u', countedCashMinor: null })
    const counted = row({ id: 'c', countedCashMinor: 30_000 })
    expect(lastClosedSession([uncounted, counted])?.id).toBe('c')
  })

  it('a zero count is a legitimate default (empty drawer overnight)', () => {
    expect(lastClosedSession([row({ countedCashMinor: 0 })])?.countedCashMinor).toBe(0)
  })
})

describe('minorToMajorInput', () => {
  it('IDR (exponent 0) passes through unscaled', () => {
    expect(minorToMajorInput(192_000, 'IDR')).toBe('192000')
    expect(minorToMajorInput(0, 'IDR')).toBe('0')
  })

  it('exponent-2 currencies render their decimals', () => {
    expect(minorToMajorInput(1234, 'USD')).toBe('12.34')
    expect(minorToMajorInput(1200, 'USD')).toBe('12.00')
  })
})
