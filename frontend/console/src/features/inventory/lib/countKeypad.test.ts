import { describe, expect, it } from 'vitest'
import {
  applyCountKey,
  COUNT_MAX_BASE_DIGITS,
  countDraftWithinCap,
  decimalSeparatorOf,
} from './countKeypad'

describe('decimalSeparatorOf', () => {
  it('writes a comma under id-ID and a dot under en-US', () => {
    expect(decimalSeparatorOf('id-ID')).toBe(',')
    expect(decimalSeparatorOf('en-US')).toBe('.')
  })
})

describe('countDraftWithinCap', () => {
  it('lets a whole-unit item hold nine base digits and no separator', () => {
    expect(countDraftWithinCap('9'.repeat(COUNT_MAX_BASE_DIGITS), false)).toBe(true)
    expect(countDraftWithinCap('9'.repeat(COUNT_MAX_BASE_DIGITS + 1), false)).toBe(false)
    expect(countDraftWithinCap('1,5', false)).toBe(false)
  })

  it('caps a display-unit item three digits lower on the whole part — a kg is a thousand grams', () => {
    // 999 999,999 kg = 999 999 999 g: the last count that still fits the base cap.
    expect(countDraftWithinCap('999999,999', true)).toBe(true)
    expect(countDraftWithinCap('1000000', true)).toBe(false)
    // More than three fraction digits would be rounded away by toBaseQty; refuse them up front.
    expect(countDraftWithinCap('1,2345', true)).toBe(false)
    expect(countDraftWithinCap('', true)).toBe(true)
  })
})

describe('applyCountKey', () => {
  it('appends digits', () => {
    expect(applyCountKey('3', '2', true)).toBe('32')
  })

  it('replaces a lone leading zero instead of producing "05"', () => {
    expect(applyCountKey('0', '5', false)).toBe('5')
    // …but not once a separator sits behind it.
    expect(applyCountKey('0,', '5', true)).toBe('0,5')
  })

  it('backspace drops the last character, and is a no-op on an empty draft', () => {
    expect(applyCountKey('3,32', 'backspace', true)).toBe('3,3')
    expect(applyCountKey('', 'backspace', true)).toBe('')
  })

  it('offers the separator only to an ingredient that admits fractions', () => {
    expect(applyCountKey('3', ',', true)).toBe('3,')
    expect(applyCountKey('3', ',', false)).toBe('3')
  })

  it('never inserts a second separator, whichever kind the first was', () => {
    expect(applyCountKey('3,3', ',', true)).toBe('3,3')
    expect(applyCountKey('3.3', ',', true)).toBe('3.3')
  })

  it('starts "0," on an empty draft — a bare "," would not parse', () => {
    expect(applyCountKey('', ',', true)).toBe('0,')
    expect(applyCountKey('', '.', true)).toBe('0.')
  })

  it('refuses a digit past the base cap, on either side of the separator', () => {
    const nine = '9'.repeat(COUNT_MAX_BASE_DIGITS)
    expect(applyCountKey(nine, '1', false)).toBe(nine)
    // kg: six whole digits is the ceiling (10^6 kg = 10^9 g); a seventh is dropped…
    expect(applyCountKey('999999', '1', true)).toBe('999999')
    // …and so is a fourth fraction digit.
    expect(applyCountKey('1,234', '5', true)).toBe('1,234')
    expect(applyCountKey('1,23', '4', true)).toBe('1,234')
  })
})
