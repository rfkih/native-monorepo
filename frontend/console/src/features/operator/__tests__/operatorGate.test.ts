import { describe, expect, it } from 'vitest'
import { operatorSheetStep, operatorSignInRequired } from '../operatorGate'

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

describe('operatorSheetStep', () => {
  it('is no-pin at a no-PIN outlet regardless of pin/manager state', () => {
    expect(operatorSheetStep({ requirePin: false, hasPin: false, managerElevated: false })).toBe(
      'no-pin',
    )
    expect(operatorSheetStep({ requirePin: false, hasPin: true, managerElevated: true })).toBe(
      'no-pin',
    )
  })

  it('is enter-pin when the employee already has a PIN', () => {
    expect(operatorSheetStep({ requirePin: true, hasPin: true, managerElevated: false })).toBe(
      'enter-pin',
    )
  })

  it('falls through to enter-pin when hasPin is unknown/missing — never forces enrollment (W2)', () => {
    expect(operatorSheetStep({ requirePin: true, hasPin: undefined, managerElevated: false })).toBe(
      'enter-pin',
    )
  })

  it('requires a manager for a PIN-less employee when none is elevated — no lone-cashier enroll (W1)', () => {
    expect(operatorSheetStep({ requirePin: true, hasPin: false, managerElevated: false })).toBe(
      'manager-required',
    )
  })

  it('allows set-pin for a PIN-less employee ONLY once a manager is elevated', () => {
    expect(operatorSheetStep({ requirePin: true, hasPin: false, managerElevated: true })).toBe(
      'set-pin',
    )
  })
})
