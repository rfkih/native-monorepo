import { describe, expect, it } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import {
  applyBp,
  computeProvisionalPricing,
  mulDivHalfEven,
  type EffectiveRulesResponse,
} from '../provisionalPricing'

describe('mulDivHalfEven — banker\'s rounding (HALF_EVEN)', () => {
  it('rounds down when the remainder is less than half', () => {
    // 100 * 1 / 3 = 33.33... -> 33
    expect(mulDivHalfEven(100n, 1n, 3n)).toBe(33n)
  })

  it('rounds up when the remainder is more than half', () => {
    // 100 * 2 / 3 = 66.66... -> 67
    expect(mulDivHalfEven(100n, 2n, 3n)).toBe(67n)
  })

  it('an EXACT half tie rounds to the EVEN quotient — down when the floor is even', () => {
    // 5 / 2 = 2.5 -> ties to 2 (even)
    expect(mulDivHalfEven(5n, 1n, 2n)).toBe(2n)
  })

  it('an EXACT half tie rounds to the EVEN quotient — up when the floor is odd', () => {
    // 7 / 2 = 3.5 -> ties to 4 (even), not 3
    expect(mulDivHalfEven(7n, 1n, 2n)).toBe(4n)
  })

  it('another half-tie pair confirms both directions (15/10 and 25/10)', () => {
    expect(mulDivHalfEven(15n, 1n, 10n)).toBe(2n) // 1.5 -> 2 (even)
    expect(mulDivHalfEven(25n, 1n, 10n)).toBe(2n) // 2.5 -> 2 (even)
    expect(mulDivHalfEven(35n, 1n, 10n)).toBe(4n) // 3.5 -> 4 (even)
  })

  it('handles large BigInt values without precision loss (well beyond Number.MAX_SAFE_INTEGER)', () => {
    const huge = 9_007_199_254_740_993n // MAX_SAFE_INTEGER + 2, not exactly representable as a double
    // huge * 1 / 2 = 4_503_599_627_370_496.5 -> ties to the even quotient 4_503_599_627_370_496
    expect(mulDivHalfEven(huge, 1n, 2n)).toBe(4_503_599_627_370_496n)
  })

  it('throws on division by zero', () => {
    expect(() => mulDivHalfEven(100n, 1n, 0n)).toThrow(RangeError)
  })

  it('applyBp applies basis points (10000bp = 100%)', () => {
    expect(applyBp(100_000n, 1_100n)).toBe(11_000n) // 11% tax on 100,000
    expect(applyBp(100_000n, 0n)).toBe(0n)
    expect(applyBp(100_000n, 10_000n)).toBe(100_000n)
  })
})

describe('computeProvisionalPricing', () => {
  const rules: EffectiveRulesResponse = {
    currency: 'IDR',
    asOf: '2026-07-31T00:00:00Z',
    taxBp: 1100, // 11%
    taxRuleVersion: 'OFFICIAL-2027.1',
    taxProvenance: 'OFFICIAL',
    serviceChargeBp: 500, // 5%
    serviceChargeInTaxBase: true,
    serviceChargeRuleVersion: 'OFFICIAL-2027.1',
    serviceChargeProvenance: 'OFFICIAL',
  }

  it('computes subtotal -> service charge -> tax -> grand total, with no discount', () => {
    const result = computeProvisionalPricing(100_000, rules)
    expect(result.subtotalMinor).toBe(100_000)
    expect(result.discountMinor).toBe(0)
    expect(result.serviceChargeMinor).toBe(5_000) // 5% of 100,000
    // taxBase = 100,000 + 5,000 = 105,000; 11% of 105,000 = 11,550
    expect(result.taxMinor).toBe(11_550)
    expect(result.grandTotalMinor).toBe(100_000 + 5_000 + 11_550)
    expect(result.currency).toBe('IDR')
    expect(result.usesCachedRules).toBe(true)
  })

  it('applies a fixed discount before service charge/tax, clamped to the subtotal', () => {
    const result = computeProvisionalPricing(100_000, rules, { fixedDiscountMinor: 20_000 })
    expect(result.discountMinor).toBe(20_000)
    const taxableBase = 80_000
    const serviceCharge = 4_000 // 5% of 80,000
    const tax = Math.round((taxableBase + serviceCharge) * 0.11)
    expect(result.serviceChargeMinor).toBe(serviceCharge)
    expect(result.taxMinor).toBe(tax)
  })

  it('clamps an oversized fixed discount to the subtotal (never negative taxable base)', () => {
    const result = computeProvisionalPricing(10_000, rules, { fixedDiscountMinor: 999_999 })
    expect(result.discountMinor).toBe(10_000)
    expect(result.serviceChargeMinor).toBe(0)
    expect(result.taxMinor).toBe(0)
    expect(result.grandTotalMinor).toBe(0)
  })

  it('when serviceChargeInTaxBase is false, tax excludes the service charge from its base', () => {
    const noScInBase: EffectiveRulesResponse = { ...rules, serviceChargeInTaxBase: false }
    const result = computeProvisionalPricing(100_000, noScInBase)
    expect(result.serviceChargeMinor).toBe(5_000)
    // taxBase = 100,000 only (service charge excluded)
    expect(result.taxMinor).toBe(11_000)
  })
})

// ---------------------------------------------------------------------------
// Shared fixture — authored by the server workstream at
// src/features/pos/offline/pricing-parity.fixture.json. Skipped (not failed) when the file has not
// landed yet, per the task instructions; runs automatically once it does.
// ---------------------------------------------------------------------------

interface ParityCase {
  name?: string
  subtotalMinor: number
  rules: EffectiveRulesResponse
  fixedDiscountMinor?: number | null
  discountBp?: number | null
  expected: {
    subtotalMinor: number
    discountMinor: number
    serviceChargeMinor: number
    taxMinor: number
    grandTotalMinor: number
    currency: string
  }
}

const fixturePath = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'pricing-parity.fixture.json',
)
const fixtureExists = existsSync(fixturePath)

describe.runIf(fixtureExists)('computeProvisionalPricing — server parity fixture', () => {
  const cases: ParityCase[] = fixtureExists ? JSON.parse(readFileSync(fixturePath, 'utf-8')) : []

  it.each(cases)('$name matches the server breakdown', (testCase) => {
    const result = computeProvisionalPricing(testCase.subtotalMinor, testCase.rules, {
      fixedDiscountMinor: testCase.fixedDiscountMinor,
      discountBp: testCase.discountBp,
    })
    expect(result.subtotalMinor).toBe(testCase.expected.subtotalMinor)
    expect(result.discountMinor).toBe(testCase.expected.discountMinor)
    expect(result.serviceChargeMinor).toBe(testCase.expected.serviceChargeMinor)
    expect(result.taxMinor).toBe(testCase.expected.taxMinor)
    expect(result.grandTotalMinor).toBe(testCase.expected.grandTotalMinor)
    expect(result.currency).toBe(testCase.expected.currency)
  })
})

if (!fixtureExists) {
  describe('computeProvisionalPricing — server parity fixture', () => {
    it.skip('pricing-parity.fixture.json has not landed yet — skipped, not failed', () => {})
  })
}
