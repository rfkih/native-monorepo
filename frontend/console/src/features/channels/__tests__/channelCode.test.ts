import { describe, expect, it } from 'vitest'
import { normalizeChannelCode } from '../channelCode'

describe('normalizeChannelCode', () => {
  it('uppercases', () => {
    expect(normalizeChannelCode('gofood')).toBe('GOFOOD')
  })

  it('strips spaces and punctuation outside A-Z0-9_-', () => {
    expect(normalizeChannelCode('Go Food!')).toBe('GOFOOD')
    expect(normalizeChannelCode('shopee.food/2')).toBe('SHOPEEFOOD2')
  })

  it('keeps hyphens and underscores', () => {
    expect(normalizeChannelCode('grab-food_v2')).toBe('GRAB-FOOD_V2')
  })

  it('empty in, empty out', () => {
    expect(normalizeChannelCode('')).toBe('')
  })
})
