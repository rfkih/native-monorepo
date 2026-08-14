import { describe, expect, it } from 'vitest'
import { isCaptureObserved, isCleanCancel } from '../billGatewayCapture'

describe('isCaptureObserved', () => {
  it('true on a fresh CAPTURED observation', () => {
    expect(isCaptureObserved('CAPTURED', false)).toBe(true)
  })

  it('false once already captured — guards the poll/manual-confirm race from double-firing', () => {
    expect(isCaptureObserved('CAPTURED', true)).toBe(false)
  })

  it('false for a non-CAPTURED status', () => {
    expect(isCaptureObserved('PENDING', false)).toBe(false)
    expect(isCaptureObserved('FAILED', false)).toBe(false)
  })

  it('false for null/undefined (no data yet)', () => {
    expect(isCaptureObserved(null, false)).toBe(false)
    expect(isCaptureObserved(undefined, false)).toBe(false)
  })
})

describe('isCleanCancel', () => {
  it('true when the cancel resolved cleanly and nothing was captured yet — abandon + fall back', () => {
    expect(isCleanCancel(false, false)).toBe(true)
  })

  it('false when the charge captured mid-cancel (capture-in-flight race) — never abandon', () => {
    expect(isCleanCancel(true, false)).toBe(false)
  })

  it('false when an earlier observation already captured — nothing left to abandon', () => {
    expect(isCleanCancel(false, true)).toBe(false)
  })

  it('false when both captured mid-cancel AND already captured', () => {
    expect(isCleanCancel(true, true)).toBe(false)
  })
})
