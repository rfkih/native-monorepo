import { describe, expect, it } from 'vitest'
import { closeBlockedByOpenBills, needsCountConfirmation } from '../closeGuard'

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

describe('closeBlockedByOpenBills (ADR 0086)', () => {
  it('any OPEN bill at the outlet blocks the close', () => {
    expect(closeBlockedByOpenBills(1)).toBe(true)
    expect(closeBlockedByOpenBills(7)).toBe(true)
  })

  it('none blocks nothing', () => {
    expect(closeBlockedByOpenBills(0)).toBe(false)
  })

  it('unknown (preview still loading/errored) never blocks — the server 409 is the backstop', () => {
    expect(closeBlockedByOpenBills(null)).toBe(false)
    expect(closeBlockedByOpenBills(undefined)).toBe(false)
  })
})
