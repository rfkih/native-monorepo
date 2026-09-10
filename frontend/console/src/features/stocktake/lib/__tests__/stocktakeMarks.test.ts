import { describe, expect, it } from 'vitest'
import {
  EMPTY_MARKS,
  saveStocktakeCount,
  stocktakeRowState,
  summarizeStocktakeProgress,
  toggleStocktakeMark,
  workedRowCount,
} from '../stocktakeMarks'

describe('stocktakeRowState', () => {
  it('starts pending: a count equal to the system quantity that nobody confirmed', () => {
    expect(stocktakeRowState(3_411, 3_411, false)).toBe('pending')
  })

  it('is verified only when the count matches AND the row was marked', () => {
    expect(stocktakeRowState(3_411, 3_411, true)).toBe('verified')
  })

  it('is changed whenever the count differs, marked or not', () => {
    expect(stocktakeRowState(3_320, 3_411, false)).toBe('changed')
    expect(stocktakeRowState(3_320, 3_411, true)).toBe('changed')
  })

  it('is invalid when the count cannot be parsed, whatever the mark says', () => {
    expect(stocktakeRowState(null, 3_411, true)).toBe('invalid')
  })
})

describe('summarizeStocktakeProgress', () => {
  it('counts verified and changed as resolved, pending and invalid separately', () => {
    expect(
      summarizeStocktakeProgress(['pending', 'verified', 'changed', 'invalid', 'pending']),
    ).toEqual({ total: 5, resolved: 2, pending: 2, invalid: 1 })
  })

  it('is all zeros for an empty list', () => {
    expect(summarizeStocktakeProgress([])).toEqual({
      total: 0,
      resolved: 0,
      pending: 0,
      invalid: 0,
    })
  })
})

describe('toggleStocktakeMark', () => {
  it('marks a pending row verified', () => {
    const next = toggleStocktakeMark(EMPTY_MARKS, 'a', 'pending')
    expect(next).toEqual({ overrides: {}, verified: { a: true } })
  })

  it('unmarks a verified row', () => {
    const next = toggleStocktakeMark({ overrides: {}, verified: { a: true } }, 'a', 'verified')
    expect(next).toEqual({ overrides: {}, verified: {} })
  })

  it('resets a changed row to the system quantity and verifies it — the typed count is dropped', () => {
    const next = toggleStocktakeMark({ overrides: { a: '3,32' }, verified: {} }, 'a', 'changed')
    expect(next).toEqual({ overrides: {}, verified: { a: true } })
  })

  it('does the same for an invalid row', () => {
    const next = toggleStocktakeMark({ overrides: { a: '' }, verified: {} }, 'a', 'invalid')
    expect(next).toEqual({ overrides: {}, verified: { a: true } })
  })

  it('never mutates the marks it was given', () => {
    const before = { overrides: { a: '1' }, verified: { b: true as const } }
    toggleStocktakeMark(before, 'a', 'changed')
    expect(before).toEqual({ overrides: { a: '1' }, verified: { b: true } })
  })
})

describe('saveStocktakeCount', () => {
  it('keeps a differing count as typed and un-verifies the row', () => {
    const next = saveStocktakeCount(
      { overrides: {}, verified: { a: true } },
      'a',
      '3,32',
      3_320,
      3_411,
    )
    expect(next).toEqual({ overrides: { a: '3,32' }, verified: {} })
  })

  it('treats the system figure typed back as a verification, not an override', () => {
    const next = saveStocktakeCount(
      { overrides: { a: '3,32' }, verified: {} },
      'a',
      '3,411',
      3_411,
      3_411,
    )
    expect(next).toEqual({ overrides: {}, verified: { a: true } })
  })

  it('keeps an unparseable count as an override so the row reads invalid', () => {
    const next = saveStocktakeCount(EMPTY_MARKS, 'a', '1,2,3', null, 3_411)
    expect(next).toEqual({ overrides: { a: '1,2,3' }, verified: {} })
  })
})

describe('workedRowCount', () => {
  it('counts distinct rows across overrides and verifications', () => {
    expect(workedRowCount(EMPTY_MARKS)).toBe(0)
    expect(workedRowCount({ overrides: { a: '1' }, verified: { b: true, c: true } })).toBe(3)
  })
})
