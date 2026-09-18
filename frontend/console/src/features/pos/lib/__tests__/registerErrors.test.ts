import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api'
import { isRegisterOpenBillsFault, registerErrorKey } from '../registerErrors'

function fault(type: string, status = 409) {
  return new ApiError(status, { type: `https://errors.nativeapp.id/${type}` }, `HTTP ${status}`)
}

describe('register fault slugs → i18n keys', () => {
  it('maps every RegisterAdvice slug the close/open form can meet', () => {
    expect(registerErrorKey(fault('register-session-already-open'))).toBe(
      'register.errorAlreadyOpen',
    )
    expect(registerErrorKey(fault('register-session-not-open'))).toBe('register.errorNotOpen')
    expect(registerErrorKey(fault('register-session-idempotency-key-conflict'))).toBe(
      'register.errorKeyConflict',
    )
    expect(registerErrorKey(fault('register-session-open-bills'))).toBe('register.errorOpenBills')
  })

  it('an unknown slug or a non-ApiError yields null (the caller falls back to the message)', () => {
    expect(registerErrorKey(fault('register-session-not-found', 404))).toBeNull()
    expect(registerErrorKey(new Error('offline'))).toBeNull()
  })

  it('isRegisterOpenBillsFault recognises only the 409 open-bills refusal', () => {
    expect(isRegisterOpenBillsFault(fault('register-session-open-bills'))).toBe(true)
    expect(isRegisterOpenBillsFault(fault('register-session-not-open'))).toBe(false)
    expect(isRegisterOpenBillsFault(fault('register-session-open-bills', 500))).toBe(false)
    expect(isRegisterOpenBillsFault(new Error('x'))).toBe(false)
  })
})
