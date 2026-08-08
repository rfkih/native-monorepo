import { describe, expect, it } from 'vitest'
import {
  FEATURE_MIN_TIER,
  tierAllowsFeature,
  toPlanTier,
  type FeatureKey,
  type PlanTier,
} from '../featureTier'

/**
 * Pure-function coverage of the tier-gating brain (P1 tier-mode,
 * `~/.claude/plans/umkm-tier-mode.md`). `useTierAccess` itself is a hook (needs `useSession`,
 * which needs a mounted SessionProvider) — this repo ships neither @testing-library/react nor a
 * DOM environment (vitest here runs `environment: 'node'`; see SessionProvider.test.ts's header
 * for the same constraint), so per the plan's own test strategy ("pure-function level is fine")
 * this suite exercises `tierAllowsFeature` / `toPlanTier` / `FEATURE_MIN_TIER` directly — the
 * exact predicates `useTierAccess` and Shell.tsx's nav filter delegate to.
 */

const ALL_FEATURES = Object.keys(FEATURE_MIN_TIER) as FeatureKey[]
const FREE_FEATURES: FeatureKey[] = [
  'pos',
  'products',
  'kitchen',
  'printer',
  'dashboard',
  'expenses',
  'team',
]
// BASIC = the rest of the operational POS surface (ADR 0047 "jalankan toko").
const BASIC_FEATURES: FeatureKey[] = ['promotions', 'channels', 'orgStructure', 'customerDisplay']
// FULL ("Premium") = everything financial (ADR 0047 "jalankan perusahaan").
const FULL_FEATURES: FeatureKey[] = ['statements', 'accounting', 'hr']

describe('tierAllowsFeature — truth table (FREE < BASIC < FULL, ADR 0047)', () => {
  it('FULL allows every feature', () => {
    for (const feature of ALL_FEATURES) {
      expect(tierAllowsFeature('FULL', feature)).toBe(true)
    }
  })

  it('BASIC allows the FREE + BASIC sets', () => {
    for (const feature of [...FREE_FEATURES, ...BASIC_FEATURES]) {
      expect(tierAllowsFeature('BASIC', feature)).toBe(true)
    }
  })

  it('BASIC hides every FULL-only (financial) feature', () => {
    for (const feature of FULL_FEATURES) {
      expect(tierAllowsFeature('BASIC', feature)).toBe(false)
    }
  })

  it('FREE allows only the FREE-tier features', () => {
    for (const feature of FREE_FEATURES) {
      expect(tierAllowsFeature('FREE', feature)).toBe(true)
    }
  })

  it('FREE hides every BASIC and FULL feature', () => {
    for (const feature of [...BASIC_FEATURES, ...FULL_FEATURES]) {
      expect(tierAllowsFeature('FREE', feature)).toBe(false)
    }
  })

  it('covers every FeatureKey across all tiers (no key silently skipped)', () => {
    const tiers: PlanTier[] = ['FREE', 'BASIC', 'FULL']
    for (const feature of ALL_FEATURES) {
      for (const tier of tiers) {
        // Every combination must resolve to a boolean — this fails loudly (throws/undefined) if
        // FEATURE_MIN_TIER is ever missing an entry the FeatureKey union grew to include.
        expect(typeof tierAllowsFeature(tier, feature)).toBe('boolean')
      }
    }
  })
})

describe('toPlanTier — fail OPEN, never fail closed (plan Risk 5)', () => {
  it('undefined resolves to FULL', () => {
    expect(toPlanTier(undefined)).toBe('FULL')
  })

  it('null resolves to FULL', () => {
    expect(toPlanTier(null)).toBe('FULL')
  })

  it('an unrecognized value resolves to FULL, not FREE', () => {
    expect(toPlanTier('bogus')).toBe('FULL')
    expect(toPlanTier('')).toBe('FULL')
  })

  it('the literal FREE resolves to FREE', () => {
    expect(toPlanTier('FREE')).toBe('FREE')
  })

  it('the literal BASIC resolves to BASIC (ADR 0047)', () => {
    expect(toPlanTier('BASIC')).toBe('BASIC')
  })

  it('the literal FULL resolves to FULL', () => {
    expect(toPlanTier('FULL')).toBe('FULL')
  })
})

describe('composed predicate — role ∧ grant ∧ tier (plan Risk 1)', () => {
  it('a page-grant-allowed, role-allowed, but tier-hidden feature stays hidden', () => {
    const roleAllows = true
    const grantAllows = true // e.g. a manager whose page grants explicitly include this page
    const tier: PlanTier = 'FREE'
    const feature: FeatureKey = 'accounting'

    const visible = roleAllows && grantAllows && tierAllowsFeature(tier, feature)

    expect(visible).toBe(false)
  })

  it('the same login on a FULL company sees the page (tier stops blocking it)', () => {
    const roleAllows = true
    const grantAllows = true
    const tier: PlanTier = 'FULL'
    const feature: FeatureKey = 'accounting'

    const visible = roleAllows && grantAllows && tierAllowsFeature(tier, feature)

    expect(visible).toBe(true)
  })

  it('tier never WIDENS access: a denied role stays hidden even on FULL', () => {
    const roleAllows = false
    const grantAllows = true
    const tier: PlanTier = 'FULL'
    const feature: FeatureKey = 'accounting'

    const visible = roleAllows && grantAllows && tierAllowsFeature(tier, feature)

    expect(visible).toBe(false)
  })
})

describe('FEATURE_MIN_TIER completeness', () => {
  const EXPECTED_KEYS: FeatureKey[] = [
    'pos',
    'products',
    'kitchen',
    'printer',
    'dashboard',
    'expenses',
    'team',
    'statements',
    'accounting',
    'promotions',
    'channels',
    'orgStructure',
    'customerDisplay',
    'hr',
  ]

  it('every FeatureKey is present exactly once', () => {
    expect(new Set(ALL_FEATURES).size).toBe(ALL_FEATURES.length)
    expect(ALL_FEATURES.sort()).toEqual([...EXPECTED_KEYS].sort())
  })

  it('dashboard/pos/products/kitchen/printer/expenses/team are FREE', () => {
    for (const feature of FREE_FEATURES) {
      expect(FEATURE_MIN_TIER[feature]).toBe('FREE')
    }
  })

  it('everything else is FULL', () => {
    for (const feature of FULL_FEATURES) {
      expect(FEATURE_MIN_TIER[feature]).toBe('FULL')
    }
  })
})
