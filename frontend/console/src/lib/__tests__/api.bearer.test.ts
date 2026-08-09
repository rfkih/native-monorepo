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
