import { describe, expect, it } from 'vitest'
import { buildOperatorSessionBody } from '../api'

describe('buildOperatorSessionBody', () => {
  it('includes pin when a non-empty PIN is supplied (PIN-required outlet)', () => {
    expect(
      buildOperatorSessionBody({ businessId: 'biz-1', employeeId: 'emp-1', pin: '1234' }),
    ).toEqual({ businessId: 'biz-1', employeeId: 'emp-1', pin: '1234' })
  })

  it('omits pin entirely — not `pin: undefined`, not `pin: ""` — when no pin is supplied (no-PIN outlet)', () => {
    const body = buildOperatorSessionBody({ businessId: 'biz-1', employeeId: 'emp-1' })
    expect(body).toEqual({ businessId: 'biz-1', employeeId: 'emp-1' })
    expect('pin' in body).toBe(false)
  })

  it('omits pin when an empty string is supplied — never sends a blank pin', () => {
    const body = buildOperatorSessionBody({ businessId: 'biz-1', employeeId: 'emp-1', pin: '' })
    expect(body).toEqual({ businessId: 'biz-1', employeeId: 'emp-1' })
    expect('pin' in body).toBe(false)
  })
})
