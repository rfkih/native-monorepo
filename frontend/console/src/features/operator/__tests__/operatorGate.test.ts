import { describe, expect, it } from 'vitest'
import { operatorSignInRequired } from '../operatorGate'

describe('operatorSignInRequired', () => {
  it('is false for a normal user login, regardless of operator state — never gates', () => {
    expect(operatorSignInRequired(false, null)).toBe(false)
    expect(operatorSignInRequired(false, { displayName: 'Ani', role: 'Cashier' })).toBe(false)
  })

  it('is true on a device terminal with no signed-in operator — fail CLOSED', () => {
    expect(operatorSignInRequired(true, null)).toBe(true)
    expect(operatorSignInRequired(true, undefined)).toBe(true)
  })

  it('is false on a device terminal once an operator is signed in', () => {
    expect(operatorSignInRequired(true, { displayName: 'Ani', role: 'Cashier' })).toBe(false)
  })
})
