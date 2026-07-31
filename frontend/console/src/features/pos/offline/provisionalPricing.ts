/**
 * provisionalPricing.ts — an EXACT client-side mirror of the server's Money.applyBasisPoints
 * formula (pricing/effective-rules), so an offline sale can show the cashier a trustworthy total
 * BEFORE the server ever sees it. Integer-only via BigInt end to end (rule 8: money is never a
 * float) — minor units cross the module boundary as `number` (safe: every intermediate value stays
 * well under Number.MAX_SAFE_INTEGER for any real transaction), converted to/from BigInt here.
 *
 * Rounding: HALF_EVEN ("banker's rounding" — ties round to the EVEN quotient), matching the
 * backend's java.math.RoundingMode.HALF_EVEN. This is NOT the same as "round half up" — 2.5 rounds
 * to 2, 3.5 rounds to 4.
 *
 * Formula order (mirrors the backend pricing engine exactly):
 *   discount      = fixedDiscountMinor ?? applyBp(subtotal, discountBp), clamped to [0, subtotal]
 *   taxableBase   = subtotal − discount
 *   serviceCharge = applyBp(taxableBase, serviceChargeBp)
 *   taxBase       = taxableBase + (serviceChargeInTaxBase ? serviceCharge : 0)
 *   tax           = applyBp(taxBase, taxBp)
 *   grandTotal    = taxableBase + serviceCharge + tax
 */

/**
 * Integer division `(amount * num) / den` rounded HALF_EVEN. General enough to handle negative
 * inputs correctly (never needed for money in this domain, but kept correct rather than assuming).
 */
export function mulDivHalfEven(amount: bigint, num: bigint, den: bigint): bigint {
  if (den === 0n) throw new RangeError('mulDivHalfEven: den must not be zero')

  const product = amount * num
  const negative = product < 0n !== den < 0n
  const absProduct = product < 0n ? -product : product
  const absDen = den < 0n ? -den : den

  const quotient = absProduct / absDen
  const remainder = absProduct % absDen
  const twiceRemainder = remainder * 2n

  let roundedAbs: bigint
  if (twiceRemainder > absDen) {
    roundedAbs = quotient + 1n
  } else if (twiceRemainder < absDen) {
    roundedAbs = quotient
  } else {
    // Exactly half — round to the EVEN quotient (banker's rounding).
    roundedAbs = quotient % 2n === 0n ? quotient : quotient + 1n
  }

  return negative ? -roundedAbs : roundedAbs
}

/** Applies basis points (1/100 of a percent — 10000bp = 100%) to an amount, HALF_EVEN rounded. */
export function applyBp(amount: bigint, bp: bigint): bigint {
  return mulDivHalfEven(amount, bp, 10_000n)
}

/**
 * Mirrors the vertical-neutral shape of `GET .../pricing/effective-rules` (backend
 * pricing.dto.EffectiveRulesResponse — a Java record). The rule-version/provenance fields are
 * opaque STRING identifiers (e.g. "OFFICIAL-2027.1"), confirmed against the server workstream's
 * pricing-parity.fixture.json — never parsed/compared numerically here, only round-tripped for
 * display/audit elsewhere. All four are `null` when the vertical has no seeded rule for that key
 * (the backend's "no-rule fall-through" — `*Bp` is then `0`, never an error). `currency` is also
 * nullable server-side (no rule seeded at all); the fetch hooks (features/pos/api.ts,
 * features/servicepos/api.ts) coerce it to the session's base currency before this type is ever
 * consumed, so `currency` is kept non-null here for every other caller.
 */
export interface EffectiveRulesResponse {
  currency: string
  asOf: string
  taxBp: number
  taxRuleVersion: string | null
  taxProvenance: string | null
  serviceChargeBp: number
  serviceChargeInTaxBase: boolean
  serviceChargeRuleVersion: string | null
  serviceChargeProvenance: string | null
}

export interface ProvisionalBreakdown {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  grandTotalMinor: number
  currency: string
  /** Always true — every provisional breakdown was computed from cached (not live) rules. */
  usesCachedRules: true
}

export interface ProvisionalPricingOptions {
  /** A manual/fixed discount in minor units — takes precedence over discountBp when supplied. */
  fixedDiscountMinor?: number | null
  /** A percentage discount in basis points, applied when no fixed discount is supplied. */
  discountBp?: number | null
}

/** Structurally mirrors both verticals' `AppliedPromotionResponse` (features/pos/api.ts,
 * features/servicepos/api.ts) — kept local to stay decoupled from either vertical's wire types. */
export interface AppliedPromotionLike {
  name: string
  type: string | null
  amountMinor: number
  lineRef: string | null
}

/**
 * Structurally mirrors both verticals' `PriceBreakdownResponse` shape so a provisional breakdown
 * can be handed straight to the SAME rendering code (PaymentModal's ModalBreakdown /
 * ServicePaymentModal's twin) that renders a live server breakdown — no offline-specific rendering
 * fork needed. `usesIllustrativeRules: true` deliberately reuses the existing "estimated" badge —
 * a provisional offline price IS an estimate, in the same spirit as an illustrative tax rate.
 */
export interface DisplayBreakdown {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  grandTotalMinor: number
  currency: string
  usesIllustrativeRules: boolean
  appliedPromotions: AppliedPromotionLike[]
  couponStatus: 'APPLIED' | 'INVALID' | 'EXHAUSTED' | null
  loyaltyRedeemedMinor: number
  giftCardAppliedMinor: number
  residualDueMinor: number
}

/** Adapts a ProvisionalBreakdown to the shape the existing (online) breakdown renderers expect. */
export function toDisplayBreakdown(p: ProvisionalBreakdown): DisplayBreakdown {
  return {
    subtotalMinor: p.subtotalMinor,
    discountMinor: p.discountMinor,
    serviceChargeMinor: p.serviceChargeMinor,
    taxMinor: p.taxMinor,
    grandTotalMinor: p.grandTotalMinor,
    currency: p.currency,
    usesIllustrativeRules: true,
    appliedPromotions: [],
    couponStatus: null,
    loyaltyRedeemedMinor: 0,
    giftCardAppliedMinor: 0,
    residualDueMinor: p.grandTotalMinor,
  }
}

/** Computes a provisional price breakdown from a cart subtotal + cached effective rules. Pure. */
export function computeProvisionalPricing(
  subtotalMinor: number,
  rules: EffectiveRulesResponse,
  opts: ProvisionalPricingOptions = {},
): ProvisionalBreakdown {
  const subtotal = BigInt(Math.trunc(subtotalMinor))
  const fixedDiscount =
    opts.fixedDiscountMinor != null ? BigInt(Math.trunc(opts.fixedDiscountMinor)) : null
  const discountBp = BigInt(Math.trunc(opts.discountBp ?? 0))

  let discount = fixedDiscount ?? applyBp(subtotal, discountBp)
  if (discount < 0n) discount = 0n
  if (discount > subtotal) discount = subtotal

  const taxableBase = subtotal - discount
  const scBp = BigInt(Math.trunc(rules.serviceChargeBp))
  const serviceCharge = applyBp(taxableBase, scBp)
  const taxBase = taxableBase + (rules.serviceChargeInTaxBase ? serviceCharge : 0n)
  const taxBp = BigInt(Math.trunc(rules.taxBp))
  const tax = applyBp(taxBase, taxBp)
  const grandTotal = taxableBase + serviceCharge + tax

  return {
    subtotalMinor: Number(subtotal),
    discountMinor: Number(discount),
    serviceChargeMinor: Number(serviceCharge),
    taxMinor: Number(tax),
    grandTotalMinor: Number(grandTotal),
    currency: rules.currency,
    usesCachedRules: true,
  }
}
