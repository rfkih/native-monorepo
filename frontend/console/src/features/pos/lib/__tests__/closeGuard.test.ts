import { describe, expect, it } from 'vitest'
import { needsCountConfirmation } from '../closeGuard'

describe('needsCountConfirmation', () => {
  it('an exact match closes straight through (no confirm)', () => {
    expect(needsCountConfirmation(192_000, 192_000)).toBe(false)
    expect(needsCountConfirmation(0, 0)).toBe(false)
  })

  it('a drawer OVER the expected requires confirmation', () => {
    expect(needsCountConfirmation(192_000, 200_000)).toBe(true)
  })

  it('a drawer SHORT of the expected requires confirmation', () => {
    expect(needsCountConfirmation(192_000, 150_000)).toBe(true)
  })

  it('unknown expected (preview still loading/errored) never blocks the close', () => {
    expect(needsCountConfirmation(null, 200_000)).toBe(false)
    expect(needsCountConfirmation(null, 0)).toBe(false)
  })

  it('an empty drawer against a non-zero expected still confirms', () => {
    expect(needsCountConfirmation(50_000, 0)).toBe(true)
  })
})
