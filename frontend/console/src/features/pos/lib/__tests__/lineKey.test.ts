import { describe, expect, it } from 'vitest'
import { lineKey } from '../lineKey'

describe('lineKey', () => {
  it('merges the same item + same options regardless of selection order', () => {
    expect(lineKey('m1', ['a', 'b'])).toBe(lineKey('m1', ['b', 'a']))
  })

  it('separates different option sets of the same item', () => {
    expect(lineKey('m1', ['a'])).not.toBe(lineKey('m1', ['a', 'b']))
    expect(lineKey('m1', [])).not.toBe(lineKey('m1', ['a']))
  })

  it('separates different items with the same options', () => {
    expect(lineKey('m1', ['a'])).not.toBe(lineKey('m2', ['a']))
  })

  it('does not mutate the caller’s option array', () => {
    const options = ['b', 'a']
    lineKey('m1', options)
    expect(options).toEqual(['b', 'a'])
  })
})
