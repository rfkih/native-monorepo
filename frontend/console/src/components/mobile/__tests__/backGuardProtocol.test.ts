/**
 * backGuardProtocol — pure-module tests (vitest runs `environment: 'node'`, no DOM). Every export
 * under test is pure over its arguments, so no `window` stub is needed.
 */
import { describe, expect, it, vi } from 'vitest'
import {
  backIntentFor,
  beginOverlaySelfPop,
  consumeOverlaySelfPop,
  isAtRoot,
  isGuardablePath,
  isGuardState,
  isOverlayState,
  isProtocolState,
  openOverlayCount,
  registerOverlay,
} from '../backGuardProtocol'

describe('state classifiers', () => {
  it('recognizes the guard sentinel', () => {
    expect(isGuardState({ backGuard: true })).toBe(true)
    expect(isGuardState({ backGuard: true, idx: 3, usr: null })).toBe(true)
  })

  it('recognizes the overlay marker', () => {
    expect(isOverlayState({ nativeBackDismiss: true })).toBe(true)
  })

  it('rejects everything else — router state, null, truthy-but-not-true markers', () => {
    for (const s of [null, undefined, {}, { idx: 2, usr: null, key: 'abc' }, { backGuard: 1 }, { nativeBackDismiss: 'yes' }]) {
      expect(isGuardState(s)).toBe(false)
      expect(isOverlayState(s)).toBe(false)
      expect(isProtocolState(s)).toBe(false)
    }
  })

  it('the markers are disjoint and isProtocolState covers both', () => {
    expect(isProtocolState({ backGuard: true })).toBe(true)
    expect(isProtocolState({ nativeBackDismiss: true })).toBe(true)
    expect(isGuardState({ nativeBackDismiss: true })).toBe(false)
    expect(isOverlayState({ backGuard: true })).toBe(false)
  })
})

describe('overlay registry (LIFO)', () => {
  it('counts registrations and only the last-registered overlay is top', () => {
    const base = openOverlayCount()
    const a = registerOverlay()
    const b = registerOverlay()
    expect(openOverlayCount()).toBe(base + 2)
    expect(b.isTop()).toBe(true)
    expect(a.isTop()).toBe(false)
    b.deregister()
    expect(a.isTop()).toBe(true)
    a.deregister()
    expect(openOverlayCount()).toBe(base)
  })

  it('deregistering out of order (parent closes both at once) still empties cleanly', () => {
    const base = openOverlayCount()
    const a = registerOverlay()
    const b = registerOverlay()
    a.deregister() // lower one first
    expect(b.isTop()).toBe(true)
    b.deregister()
    expect(openOverlayCount()).toBe(base)
  })

  it('double deregister is harmless', () => {
    const base = openOverlayCount()
    const a = registerOverlay()
    a.deregister()
    a.deregister()
    expect(openOverlayCount()).toBe(base)
  })
})

describe('isGuardablePath', () => {
  it('excludes the customer display and onboarding', () => {
    expect(isGuardablePath('/pos/customer-display')).toBe(false)
    expect(isGuardablePath('/pos/customer-display/2')).toBe(false)
    expect(isGuardablePath('/onboarding')).toBe(false)
    expect(isGuardablePath('/onboarding/company')).toBe(false)
  })

  it('guards everything else, including /pos itself', () => {
    for (const p of ['/', '/pos', '/me', '/me/expenses/42', '/statements/income', '/menu']) {
      expect(isGuardablePath(p)).toBe(true)
    }
  })
})

describe('isAtRoot', () => {
  it('home path is always root regardless of idx', () => {
    expect(isAtRoot('/me', { idx: 7 }, '/me')).toBe(true)
  })

  it('the first in-app entry (idx <= 0) is root even off-home — never go(-2) into the IdP', () => {
    expect(isAtRoot('/me/expenses/42', { idx: 0 }, '/me')).toBe(true)
    expect(isAtRoot('/statements/income', { idx: -1 }, '/')).toBe(true)
  })

  it('missing/foreign state (no idx) counts as root — fail toward the exit dialog, not a bounce', () => {
    expect(isAtRoot('/team', null, '/')).toBe(true)
    expect(isAtRoot('/team', {}, '/')).toBe(true)
    expect(isAtRoot('/team', { idx: 'x' }, '/')).toBe(true)
  })

  it('deeper entries off-home are not root', () => {
    expect(isAtRoot('/team', { idx: 3 }, '/')).toBe(false)
  })
})

describe('overlay self-pop consumption', () => {
  it('one pending pop is consumed by the first handler and stays consumed for the same event', () => {
    const event = { type: 'popstate' }
    expect(consumeOverlaySelfPop(event)).toBe(false) // nothing pending
    beginOverlaySelfPop()
    expect(consumeOverlaySelfPop(event)).toBe(true) // first handler consumes
    expect(consumeOverlaySelfPop(event)).toBe(true) // later handlers of the SAME event agree
    expect(consumeOverlaySelfPop({ type: 'popstate' })).toBe(false) // a NEW event is not self
  })

  it('a token is consumed even when nothing is mounted to see the unwind', () => {
    // The regression: in a plain browser (no route guard) with the last overlay already unmounted,
    // NOBODY handled the unwind's popstate, so the token survived and swallowed the user's next
    // real Back press. `beginOverlaySelfPop` now registers its own one-shot backstop.
    const listeners: Array<(e: Event) => void> = []
    vi.stubGlobal('window', {
      addEventListener: (type: string, fn: (e: Event) => void) => {
        if (type === 'popstate') listeners.push(fn)
      },
    })
    beginOverlaySelfPop()
    expect(listeners).toHaveLength(1)

    const unwind = { type: 'popstate' } as unknown as Event
    listeners[0](unwind) // the backstop fires — nothing else was listening
    // The user's next, genuine Back must NOT read as a self-pop.
    expect(consumeOverlaySelfPop({ type: 'popstate' })).toBe(false)
    vi.unstubAllGlobals()
  })

  it('the backstop and a live overlay handler agree on the same event', () => {
    const listeners: Array<(e: Event) => void> = []
    vi.stubGlobal('window', {
      addEventListener: (type: string, fn: (e: Event) => void) => {
        if (type === 'popstate') listeners.push(fn)
      },
    })
    beginOverlaySelfPop()
    const unwind = { type: 'popstate' } as unknown as Event
    // Whichever runs first decides; the other must still read "already consumed", never decrement
    // a second token.
    expect(consumeOverlaySelfPop(unwind)).toBe(true)
    listeners[0](unwind)
    expect(consumeOverlaySelfPop({ type: 'popstate' })).toBe(false)
    vi.unstubAllGlobals()
  })

  it('pending pops pair 1:1 with distinct events', () => {
    beginOverlaySelfPop()
    beginOverlaySelfPop()
    const e1 = {}
    const e2 = {}
    const e3 = {}
    expect(consumeOverlaySelfPop(e1)).toBe(true)
    expect(consumeOverlaySelfPop(e2)).toBe(true)
    expect(consumeOverlaySelfPop(e3)).toBe(false)
    expect(consumeOverlaySelfPop(e2)).toBe(true) // idempotent for the LATEST event only —
    expect(consumeOverlaySelfPop(e1)).toBe(false) // events dispatch serially, so that suffices
  })
})

describe('backIntentFor', () => {
  it('skips the parked sentinel with -2 when guarded', () => {
    expect(backIntentFor({ backGuard: true, idx: 4 })).toEqual({ kind: 'pop', delta: -2 })
  })

  it('plain -1 when unguarded (browser / excluded route)', () => {
    expect(backIntentFor({ idx: 4, usr: null })).toEqual({ kind: 'pop', delta: -1 })
  })

  it('the first in-app entry has nothing to pop — use the declared fallback', () => {
    // A deep link / cold open straight onto a sub-page: popping here leaves for the IdP page.
    expect(backIntentFor({ idx: 0, usr: null })).toEqual({ kind: 'fallback' })
    expect(backIntentFor({ backGuard: true, idx: 0 })).toEqual({ kind: 'fallback' })
    expect(backIntentFor({ idx: -1 })).toEqual({ kind: 'fallback' })
  })

  it('missing or foreign state falls back rather than guessing a delta', () => {
    for (const s of [null, undefined, {}, { idx: 'x' }, { idx: null }]) {
      expect(backIntentFor(s)).toEqual({ kind: 'fallback' })
    }
  })

  it('never eats an overlay entry — that pop belongs to useBackDismiss', () => {
    expect(backIntentFor({ nativeBackDismiss: true })).toEqual({ kind: 'fallback' })
    // …even when the overlay entry carries a usable idx (adopted entry, stacked sheets).
    expect(backIntentFor({ nativeBackDismiss: true, idx: 4 })).toEqual({ kind: 'fallback' })
  })
})
