/**
 * backGuardProtocol — pure-module tests (vitest runs `environment: 'node'`, no DOM; the browser-
 * touching helpers are exercised against a minimal stubbed `window`, following the MemoryStorage
 * precedent in lib/__tests__/SessionProvider.test.ts).
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  beginOverlaySelfPop,
  consumeOverlaySelfPop,
  guardedNavigateBack,
  isAtRoot,
  isGuardablePath,
  isGuardState,
  isOverlayState,
  isProtocolState,
  openOverlayCount,
  registerOverlay,
} from '../backGuardProtocol'

afterEach(() => {
  vi.unstubAllGlobals()
})

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

describe('guardedNavigateBack', () => {
  it('skips the parked sentinel with -2 when guarded', () => {
    vi.stubGlobal('window', { history: { state: { backGuard: true, idx: 4 } } })
    const navigate = vi.fn()
    guardedNavigateBack(navigate)
    expect(navigate).toHaveBeenCalledWith(-2)
  })

  it('plain -1 when unguarded (browser / excluded route)', () => {
    vi.stubGlobal('window', { history: { state: { idx: 4, usr: null } } })
    const navigate = vi.fn()
    guardedNavigateBack(navigate)
    expect(navigate).toHaveBeenCalledWith(-1)
  })
})
