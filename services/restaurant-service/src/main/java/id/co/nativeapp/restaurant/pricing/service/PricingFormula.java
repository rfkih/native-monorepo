package id.co.nativeapp.restaurant.pricing.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;

/**
 * The Phase 2 pricing formula (round ONCE per component via {@link Money#applyBasisPoints}),
 * factored out of {@link TaxChargeService#resolve} so it can be exercised directly:
 *
 * <ul>
 *   <li>by {@link TaxChargeService}, with SERVER-RESOLVED {@code tax_charge_rule} rates;
 *   <li>by {@code pricing.service.ProvisionalPricingFixtureTest}, with the exact inputs the
 *       client's offline-mode provisional-pricing fixture (ADR 0028) asserts against.
 * </ul>
 *
 * <p>Both callers exercise this SAME code path, so a change here that breaks the fixture test is
 * precisely the server/client pricing drift the offline parity contract exists to catch.
 *
 * <ol>
 *   <li>{@code discount} = percent ({@code subtotal.applyBasisPoints(discountBp)}) OR fixed Money;
 *       clamped to {@code discount.min(subtotal)} so discount &le; subtotal always holds
 *   <li>{@code taxableBase = subtotal − discount}
 *   <li>{@code serviceCharge = taxableBase.applyBasisPoints(serviceChargeBp)}
 *   <li>{@code taxBase = taxableBase [+ serviceCharge if serviceChargeInTaxBase]}
 *   <li>{@code tax = taxBase.applyBasisPoints(taxBp)}
 *   <li>{@code grandTotal = taxableBase + serviceCharge + tax}
 * </ol>
 *
 * <p>Package-private: an internal pricing detail, not a public API.
 */
final class PricingFormula {

  private PricingFormula() {}

  /**
   * Computes the {@link PriceBreakdown} for the given subtotal and resolved rule rates.
   *
   * @param subtotal the pre-computed order subtotal (Σ line totals)
   * @param discountBp basis-point percent discount (0 = no discount); applied on subtotal
   * @param fixedDiscount optional fixed Money discount to use INSTEAD of a percent discount (when
   *     non-null, overrides {@code discountBp}); must be in the same currency as subtotal
   * @param serviceChargeBp basis-point service-charge rate, applied on the taxable base
   * @param serviceChargeInTaxBase whether the service charge is included in the tax base
   * @param taxBp basis-point tax rate, applied on the tax base
   * @param taxRuleVersion the resolved tax rule's version label, carried through unchanged (may be
   *     {@code null})
   * @param usesIllustrativeRules whether any resolved rule was {@code ILLUSTRATIVE_PLACEHOLDER},
   *     carried through unchanged
   * @return the fully computed {@link PriceBreakdown}
   */
  static PriceBreakdown compute(
      Money subtotal,
      long discountBp,
      Money fixedDiscount,
      long serviceChargeBp,
      boolean serviceChargeInTaxBase,
      long taxBp,
      String taxRuleVersion,
      boolean usesIllustrativeRules) {

    // Discount — percent OR fixed, clamped to ≤ subtotal.
    Money discount;
    if (fixedDiscount != null) {
      discount = fixedDiscount.min(subtotal);
    } else {
      discount = subtotal.applyBasisPoints(discountBp).min(subtotal);
    }

    // Taxable base.
    Money taxableBase = subtotal.minus(discount);

    // Service charge (on taxable base).
    Money serviceCharge = taxableBase.applyBasisPoints(serviceChargeBp);

    // Tax base: taxableBase [+ serviceCharge if config flag].
    Money taxBase = serviceChargeInTaxBase ? taxableBase.plus(serviceCharge) : taxableBase;

    // Tax.
    Money tax = taxBase.applyBasisPoints(taxBp);

    // Grand total.
    Money grandTotal = taxableBase.plus(serviceCharge).plus(tax);

    return new PriceBreakdown(
        subtotal,
        discount,
        taxableBase,
        serviceCharge,
        tax,
        grandTotal,
        taxRuleVersion,
        usesIllustrativeRules);
  }
}
