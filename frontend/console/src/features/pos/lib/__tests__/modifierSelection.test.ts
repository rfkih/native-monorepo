import { describe, it, expect } from 'vitest'
import { singleSelectionSet } from '../modifierSelection'

describe('singleSelectionSet', () => {
  it('selects a single option id', () => {
    expect([...singleSelectionSet('opt-1')]).toEqual(['opt-1'])
  })

  it('is authoritative — the result holds only the given option, never accumulates', () => {
    // The reducer is stateless: picking an option yields a set of exactly that one id.
    const set = singleSelectionSet('opt-2')
    expect([...set]).toEqual(['opt-2'])
    expect(set.size).toBe(1)
  })

  it('clears the selection for an empty id (deselect / skip an optional group)', () => {
    expect(singleSelectionSet('').size).toBe(0)
  })

  it('never produces a phantom empty-string option id', () => {
    // Guards the regression: Set(['']) would leak an empty id into the confirmed line.
    expect(singleSelectionSet('').has('')).toBe(false)
  })
})
