import { describe, expect, it } from 'vitest'
import { selectBearerToken } from '../api'

/**
 * Pure-function coverage of the ADR 0049 P3b bearer selection — {@link selectBearerToken} is the
 * ONLY thing `authHeaders` (module-private, closed over `AUTH_MODE`/live token state) delegates to
 * for picking outlet vs personal, so this is the testable seam (mirrors featureTier.test.ts's
 * "exercise the pure predicate directly" strategy for a hook that isn't otherwise unit-testable in
 * this repo's DOM-less `environment: 'node'` vitest setup).
 */
describe('selectBearerToken — ADR 0049 P3b outlet vs personal bearer selection', () => {
  it('defaults semantics: "outlet" picks the outlet token', () => {
    expect(selectBearerToken('outlet', { outlet: 'outlet-tok', personal: 'personal-tok' })).toBe(
      'outlet-tok',
    )
  })

  it('"personal" picks the personal token', () => {
    expect(selectBearerToken('personal', { outlet: 'outlet-tok', personal: 'personal-tok' })).toBe(
      'personal-tok',
    )
  })

  it('a normal `user` login mirrors the same token into both slots — either target resolves identically', () => {
    const tokens = { outlet: 'same-token', personal: 'same-token' }
    expect(selectBearerToken('outlet', tokens)).toBe('same-token')
    expect(selectBearerToken('personal', tokens)).toBe('same-token')
  })

  it('an unelevated device terminal has no personal bearer — "personal" resolves to null (fail closed, never falls back to outlet)', () => {
    expect(selectBearerToken('personal', { outlet: 'outlet-tok', personal: null })).toBeNull()
  })

  it('a device with no outlet session yet (edge case) has no outlet bearer either', () => {
    expect(selectBearerToken('outlet', { outlet: null, personal: null })).toBeNull()
  })

  it('an elevated device resolves "personal" to the elevation token, independent of the outlet token', () => {
    expect(
      selectBearerToken('personal', { outlet: 'device-outlet-tok', personal: 'elevation-tok' }),
    ).toBe('elevation-tok')
  })
})

/**
 * `'elevated'` — the strongest credential this login holds. For a POS write the SERVICE gates on
 * owner/manager only in some states (cancelling a bill WITH lines) and leaves open to a bare cashier
 * in others (cancelling an EMPTY bill), so the call must carry the elevation when there is one and
 * still carry the outlet credential when there is not. `'personal'` alone would send NO bearer on an
 * un-elevated device (a 401 that trips the auth layer's recovery); `'outlet'` alone hides the
 * elevation from the server — the bug that left an elevated owner unable to cancel from the phone.
 */
describe('selectBearerToken — "elevated" (personal when present, else outlet)', () => {
  it('an elevated device rides the elevation token', () => {
    expect(
      selectBearerToken('elevated', { outlet: 'device-outlet-tok', personal: 'elevation-tok' }),
    ).toBe('elevation-tok')
  })

  it('an un-elevated device falls back to the outlet credential (a bare cashier may still cancel an empty bill)', () => {
    expect(selectBearerToken('elevated', { outlet: 'device-outlet-tok', personal: null })).toBe(
      'device-outlet-tok',
    )
  })

  it('a normal `user` login resolves to its one token', () => {
    expect(selectBearerToken('elevated', { outlet: 'same-token', personal: 'same-token' })).toBe(
      'same-token',
    )
  })

  it('no session at all resolves to null', () => {
    expect(selectBearerToken('elevated', { outlet: null, personal: null })).toBeNull()
  })

  it('does not loosen "personal": an un-elevated device still gets null there (fail closed)', () => {
    expect(selectBearerToken('personal', { outlet: 'device-outlet-tok', personal: null })).toBeNull()
  })
})
