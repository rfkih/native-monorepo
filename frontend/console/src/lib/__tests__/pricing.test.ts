import { describe, expect, it } from 'vitest'
import { computeMonthlyPrice, PRICING } from '../pricing'

/**
 * The ADR 0047 subscription formula, boundary by boundary:
 *   total = base + 49rb × max(0, outlets − included) + 50rb × ceil(max(0, employees − 10) / 20)
 * All integer minor units (IDR exponent 0). FREE is never billable — overage informs the
 * upgrade prompt, not a charge.
 */
describe('computeMonthlyPrice — outlet add-on boundaries (2 included on paid tiers)', () => {
  it('2 outlets on BASIC = base only', () => {
    const p = computeMonthlyPrice('BASIC', 2, 10)
    expect(p.extraOutlets).toBe(0)
    expect(p.outletAddOnMinor).toBe(0)
    expect(p.totalMinor).toBe(149_000)
    expect(p.withinIncluded).toBe(true)
  })

  it('3 outlets on BASIC = base + one 49rb add-on', () => {
    const p = computeMonthlyPrice('BASIC', 3, 10)
    expect(p.extraOutlets).toBe(1)
    expect(p.outletAddOnMinor).toBe(49_000)
    expect(p.totalMinor).toBe(198_000)
    expect(p.withinIncluded).toBe(false)
  })

  it('5 outlets on FULL (Premium) = base + 3 × 49rb', () => {
    const p = computeMonthlyPrice('FULL', 5, 10)
    expect(p.extraOutlets).toBe(3)
    expect(p.totalMinor).toBe(299_000 + 3 * 49_000)
  })
})

describe('computeMonthlyPrice — employee 20-pack boundaries (10 included)', () => {
  it('10 employees = no pack', () => {
    expect(computeMonthlyPrice('BASIC', 2, 10).employeePacks).toBe(0)
  })

  it('11 employees = 1 started pack (+50rb)', () => {
    const p = computeMonthlyPrice('BASIC', 2, 11)
    expect(p.employeePacks).toBe(1)
    expect(p.employeeAddOnMinor).toBe(50_000)
    expect(p.totalMinor).toBe(199_000)
  })

  it('30 employees = still 1 pack (10 + 20 exactly)', () => {
    expect(computeMonthlyPrice('BASIC', 2, 30).employeePacks).toBe(1)
  })

  it('31 employees = 2 packs', () => {
    const p = computeMonthlyPrice('BASIC', 2, 31)
    expect(p.employeePacks).toBe(2)
    expect(p.employeeAddOnMinor).toBe(100_000)
  })

  it('0 employees stays at zero packs (no negative overage)', () => {
    expect(computeMonthlyPrice('FULL', 1, 0).employeePacks).toBe(0)
  })
})

describe('computeMonthlyPrice — FREE is informational, never billable', () => {
  it('within limits: total 0, withinIncluded true', () => {
    const p = computeMonthlyPrice('FREE', 1, 10)
    expect(p.totalMinor).toBe(0)
    expect(p.withinIncluded).toBe(true)
  })

  it('over limits: overage counted for the upgrade prompt, but NOTHING is charged', () => {
    const p = computeMonthlyPrice('FREE', 3, 25)
    expect(p.extraOutlets).toBe(2)
    expect(p.employeePacks).toBe(1)
    expect(p.outletAddOnMinor).toBe(0)
    expect(p.employeeAddOnMinor).toBe(0)
    expect(p.totalMinor).toBe(0)
    expect(p.withinIncluded).toBe(false)
  })
})

describe('the price sheet itself', () => {
  it('is IDR with the ADR 0047 published numbers', () => {
    expect(PRICING.currency).toBe('IDR')
    expect(PRICING.tiers.FREE.baseMinor).toBe(0)
    expect(PRICING.tiers.BASIC.baseMinor).toBe(149_000)
    expect(PRICING.tiers.FULL.baseMinor).toBe(299_000)
    expect(PRICING.extraOutletMinor).toBe(49_000)
    expect(PRICING.employeePackMinor).toBe(50_000)
    expect(PRICING.employeePackSize).toBe(20)
    expect(PRICING.employeesIncluded).toBe(10)
  })
})
