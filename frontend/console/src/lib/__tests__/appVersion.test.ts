import { describe, it, expect } from 'vitest'
import { isOutdated } from '../appVersion'

describe('isOutdated', () => {
  it('is true only when a different, non-empty latest build is known', () => {
    expect(isOutdated('abc123', 'def456')).toBe(true)
  })

  it('is false when latest equals the running build', () => {
    expect(isOutdated('abc123', 'abc123')).toBe(false)
  })

  it('fails CLOSED — a null/undefined/empty latest (offline, failed fetch, dev) is up-to-date', () => {
    expect(isOutdated('abc123', null)).toBe(false)
    expect(isOutdated('abc123', undefined)).toBe(false)
    expect(isOutdated('abc123', '')).toBe(false)
  })
})
