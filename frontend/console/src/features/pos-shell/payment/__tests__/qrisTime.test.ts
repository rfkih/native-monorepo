import { describe, expect, it } from 'vitest'
import { formatCountdown, isExpired, msLeft } from '../qrisTime'

describe('msLeft', () => {
  it('returns the remaining milliseconds', () => {
    expect(msLeft(10_000, 3_000)).toBe(7_000)
  })

  it('floors at 0 — never negative once past the deadline', () => {
    expect(msLeft(10_000, 15_000)).toBe(0)
    expect(msLeft(10_000, 10_000)).toBe(0)
  })
})

describe('isExpired', () => {
  it('false while time remains', () => {
    expect(isExpired(10_000, 9_999)).toBe(false)
  })

  it('true exactly at the deadline (inclusive) and past it', () => {
    expect(isExpired(10_000, 10_000)).toBe(true)
    expect(isExpired(10_000, 10_001)).toBe(true)
  })
})

describe('formatCountdown', () => {
  it('formats minutes:seconds, zero-padded seconds', () => {
    expect(formatCountdown(14 * 60_000 + 59_000)).toBe('14:59')
    expect(formatCountdown(7_000)).toBe('0:07')
  })

  it('formats exactly zero as 0:00', () => {
    expect(formatCountdown(0)).toBe('0:00')
  })

  it('never goes negative for an over-expired duration', () => {
    expect(formatCountdown(-5_000)).toBe('0:00')
  })

  it('floors partial seconds rather than rounding', () => {
    expect(formatCountdown(1_999)).toBe('0:01')
  })
})
