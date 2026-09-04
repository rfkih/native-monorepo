import { describe, expect, it } from 'vitest'
import { displayLoginId } from './loginId'

describe('displayLoginId', () => {
  it('prefixes a stripped local with the company code', () => {
    expect(displayLoginId('leha', 'dg7chf')).toBe('dg7chf.leha')
  })

  it('is idempotent — never double-prefixes an already-full id', () => {
    expect(displayLoginId('dg7chf.leha', 'dg7chf')).toBe('dg7chf.leha')
  })

  it('leaves an owner email untouched (no company prefix)', () => {
    expect(displayLoginId('owner@example.com', 'dg7chf')).toBe('owner@example.com')
  })

  it('falls back to the bare local when no company code is available', () => {
    expect(displayLoginId('leha', '')).toBe('leha')
    expect(displayLoginId('leha', null)).toBe('leha')
    expect(displayLoginId('leha', undefined)).toBe('leha')
  })

  it('returns empty string for an absent username', () => {
    expect(displayLoginId(null, 'dg7chf')).toBe('')
    expect(displayLoginId(undefined, 'dg7chf')).toBe('')
    expect(displayLoginId('   ', 'dg7chf')).toBe('')
  })

  it('trims surrounding whitespace before composing', () => {
    expect(displayLoginId('  leha  ', ' dg7chf ')).toBe('dg7chf.leha')
  })
})
