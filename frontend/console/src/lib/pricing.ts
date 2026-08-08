/**
 * The Native price sheet + subscription math (ADR 0047 — Gratis / Basic / Premium with usage
 * add-ons). This module is the SINGLE adjustable source of the published prices: the plan page
 * (/settings/features) and the landing pricing section both render from it, so changing a number
 * here changes it everywhere.
 *
 * Money rule (rule 8): every amount is an integer in minor units with an ISO-4217 currency (IDR
 * exponent 0 — minor == rupiah); rendering always goes through formatMoney. The formula is pure
 * and unit-tested (src/lib/__tests__/pricing.test.ts).
 *
 * DISPLAY + SOFT-ENFORCE phase (ADR 0047): the computed total is what the owner is quoted; no
 * hard caps, collection is manual. When real billing lands, this sheet moves server-side and the
 * tier setter is replaced by the billing event — the reader path stays (ADR 0044's property).
 */
import type { PlanTier } from '@/lib/featureTier'

export const PRICING = {
  currency: 'IDR',
  /** Per-tier base subscription + how many outlets the base already covers. */
  tiers: {
    FREE: { baseMinor: 0, outletsIncluded: 1 },
    BASIC: { baseMinor: 149_000, outletsIncluded: 2 },
    FULL: { baseMinor: 299_000, outletsIncluded: 2 },
  } satisfies Record<PlanTier, { baseMinor: number; outletsIncluded: number }>,
  /** Employees included in every tier's base price. */
  employeesIncluded: 10,
  /** Monthly add-on per outlet beyond the tier's included count (paid tiers). */
  extraOutletMinor: 49_000,
  /** Monthly add-on per STARTED pack of `employeePackSize` employees beyond the included 10. */
  employeePackMinor: 50_000,
  employeePackSize: 20,
} as const

export interface MonthlyPrice {
  tier: PlanTier
  currency: string
  baseMinor: number
  /** Outlets beyond the tier's included count (0 when within). */
  extraOutlets: number
  outletAddOnMinor: number
  /** Started 20-packs beyond the included employees (0 when within). */
  employeePacks: number
  employeeAddOnMinor: number
  totalMinor: number
  /** True when usage fits the tier's included counts — drives the soft-enforce callout. */
  withinIncluded: boolean
}

/**
 * The quoted monthly price for a tier at the given usage. FREE computes the same overage
 * fields (so the plan page can SAY "this usage needs an upgrade") but its list price stays 0 —
 * FREE is not billable; the overage is an upgrade prompt, not a charge (ADR 0047).
 */
export function computeMonthlyPrice(
  tier: PlanTier,
  outletCount: number,
  employeeCount: number,
): MonthlyPrice {
  const sheet = PRICING.tiers[tier]
  const extraOutlets = Math.max(0, outletCount - sheet.outletsIncluded)
  const employeePacks = Math.ceil(
    Math.max(0, employeeCount - PRICING.employeesIncluded) / PRICING.employeePackSize,
  )
  const billable = tier !== 'FREE'
  const outletAddOnMinor = billable ? extraOutlets * PRICING.extraOutletMinor : 0
  const employeeAddOnMinor = billable ? employeePacks * PRICING.employeePackMinor : 0
  return {
    tier,
    currency: PRICING.currency,
    baseMinor: sheet.baseMinor,
    extraOutlets,
    outletAddOnMinor,
    employeePacks,
    employeeAddOnMinor,
    totalMinor: sheet.baseMinor + outletAddOnMinor + employeeAddOnMinor,
    withinIncluded: extraOutlets === 0 && employeePacks === 0,
  }
}
