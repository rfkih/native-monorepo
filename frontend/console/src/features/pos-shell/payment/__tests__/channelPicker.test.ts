import { describe, expect, it } from 'vitest'
import { activeChannels, isSelectableChannel, type SalesChannelLike } from '../channelPicker'

const CHANNELS: SalesChannelLike[] = [
  { id: '1', code: 'GOFOOD', name: 'GoFood', active: true },
  { id: '2', code: 'GRABFOOD', name: 'GrabFood', active: true },
  { id: '3', code: 'RETIRED', name: 'Retired platform', active: false },
]

describe('activeChannels', () => {
  it('keeps only active channels, preserving order', () => {
    expect(activeChannels(CHANNELS)).toEqual([
      { id: '1', code: 'GOFOOD', name: 'GoFood', active: true },
      { id: '2', code: 'GRABFOOD', name: 'GrabFood', active: true },
    ])
  })

  it('empty in, empty out', () => {
    expect(activeChannels([])).toEqual([])
  })
})

describe('isSelectableChannel', () => {
  it('true for an active channel code present in the list', () => {
    expect(isSelectableChannel(CHANNELS, 'GOFOOD')).toBe(true)
  })

  it('false for null/empty — no channel picked yet', () => {
    expect(isSelectableChannel(CHANNELS, null)).toBe(false)
    expect(isSelectableChannel(CHANNELS, '')).toBe(false)
  })

  it('false for an inactive channel — a stale selection cannot sneak through', () => {
    expect(isSelectableChannel(CHANNELS, 'RETIRED')).toBe(false)
  })

  it('false for a code that does not exist in the list at all', () => {
    expect(isSelectableChannel(CHANNELS, 'SHOPEEFOOD')).toBe(false)
  })
})
