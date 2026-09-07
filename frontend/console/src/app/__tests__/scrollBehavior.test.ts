/**
 * scrollBehavior — pure-module tests (vitest runs `environment: 'node'`). The DOM-touching
 * `applyScrollAction` is covered by scripts/nav-smoke.mjs instead; everything decided BEFORE
 * touching the DOM is decided here.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearScrollMemory,
  navKindOf,
  recallScroll,
  rememberScroll,
  scrollActionFor,
} from '../scrollBehavior'

describe('navKindOf', () => {
  it('passes through the three router actions', () => {
    expect(navKindOf('PUSH')).toBe('PUSH')
    expect(navKindOf('POP')).toBe('POP')
    expect(navKindOf('REPLACE')).toBe('REPLACE')
  })

  it('falls back to POP for anything unrecognised', () => {
    // POP is the conservative branch: it restores or goes to the top, never yanking the page.
    expect(navKindOf('')).toBe('POP')
    expect(navKindOf('TRAVERSE')).toBe('POP')
  })
})

describe('scrollActionFor', () => {
  it('a new destination starts at the top', () => {
    expect(scrollActionFor({ navigationType: 'PUSH', hash: '', saved: undefined })).toEqual({
      kind: 'top',
    })
    // Even when this key was visited before — PUSH means a NEW entry, not a return.
    expect(scrollActionFor({ navigationType: 'PUSH', hash: '', saved: 500 })).toEqual({
      kind: 'top',
    })
  })

  it('going back restores where you were', () => {
    expect(scrollActionFor({ navigationType: 'POP', hash: '', saved: 640 })).toEqual({
      kind: 'restore',
      offset: 640,
    })
    // A remembered 0 is still a memory, not an absence — restore it rather than "resetting".
    expect(scrollActionFor({ navigationType: 'POP', hash: '', saved: 0 })).toEqual({
      kind: 'restore',
      offset: 0,
    })
  })

  it('going back to an entry we never rendered falls back to the top', () => {
    // A reload, or history from before this session: guessing an offset would be worse.
    expect(scrollActionFor({ navigationType: 'POP', hash: '', saved: undefined })).toEqual({
      kind: 'top',
    })
  })

  it('a REPLACE never moves the page', () => {
    // Guard redirects (/me/payslips -> /me at tablet width) and tab-to-URL syncing are REPLACE;
    // yanking the viewport during one reads as a glitch, not as navigation.
    for (const saved of [undefined, 0, 900]) {
      expect(scrollActionFor({ navigationType: 'REPLACE', hash: '', saved })).toEqual({
        kind: 'none',
      })
    }
  })

  it('a hash owns its own position, whatever the navigation type', () => {
    for (const navigationType of ['PUSH', 'POP', 'REPLACE'] as const) {
      expect(scrollActionFor({ navigationType, hash: '#pricing', saved: 300 })).toEqual({
        kind: 'none',
      })
    }
  })
})

describe('scroll memory', () => {
  beforeEach(() => {
    clearScrollMemory()
  })

  it('remembers and recalls per location key', () => {
    rememberScroll('a', 120)
    rememberScroll('b', 0)
    expect(recallScroll('a')).toBe(120)
    expect(recallScroll('b')).toBe(0)
    expect(recallScroll('never-seen')).toBeUndefined()
  })

  it('re-remembering a key overwrites rather than duplicates', () => {
    rememberScroll('a', 120)
    rememberScroll('a', 340)
    expect(recallScroll('a')).toBe(340)
  })

  it('stays bounded, evicting the least recently left page', () => {
    for (let i = 0; i < 60; i++) rememberScroll(`k${i}`, i)
    expect(recallScroll('k0')).toBeUndefined() // evicted
    expect(recallScroll('k59')).toBe(59) // newest kept
    expect(recallScroll('k20')).toBe(20) // inside the window
  })

  it('touching an old key keeps it alive through eviction', () => {
    for (let i = 0; i < 40; i++) rememberScroll(`k${i}`, i)
    rememberScroll('k0', 7) // revisited, so it moves to the newest end
    for (let i = 40; i < 60; i++) rememberScroll(`k${i}`, i)
    expect(recallScroll('k0')).toBe(7)
    expect(recallScroll('k1')).toBeUndefined()
  })
})
