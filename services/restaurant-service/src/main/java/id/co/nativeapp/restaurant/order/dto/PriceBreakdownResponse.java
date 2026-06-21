package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;

/**
 * A structured snapshot of the Phase 2 price breakdown for one order or quote.
 *
 * <p>All amounts are in minor currency units (same {@code currency} as the order). The {@code
 * grandTotalMinor} field equals the order's {@code totalMinor} — it is duplicated here so the UI
 * can render a single self-contained breakdown block without having to join with the parent order
 * field.
 *
 * <p>The {@code usesIllustrativeRules} flag is {@code true} when any resolved {@code
 * tax_charge_rule} row carried {@code provenance = ILLUSTRATIVE_PLACEHOLDER}. The POS must badge
 * the tax / service-charge lines as "estimated" when this is {@code true} so cashiers are not
 * misled into believing the amounts are regulatory-verified.
 *
 * @param subtotalMinor sum of line totals before any discount (minor units)
 * @param discountMinor order-level discount applied (clamped to &le; subtotal; minor units)
 * @param serviceChargeMinor service charge amount (minor units)
 * @param taxMinor tax amount (minor units)
 * @param grandTotalMinor the customer-pays amount: subtotal − discount + serviceCharge + tax
 * @param currency ISO-4217 code for all amounts in this breakdown
 * @param usesIllustrativeRules {@code true} when any resolved pricing rule was
 *     ILLUSTRATIVE_PLACEHOLDER; the UI should badge tax / SC as estimated
 */
public record PriceBreakdownResponse(
    long subtotalMinor,
    long discountMinor,
    long serviceChargeMinor,
    long taxMinor,
    long grandTotalMinor,
    String currency,
    boolean usesIllustrativeRules) {

  /** Factory: maps a domain {@link PriceBreakdown} to the wire shape. */
  public static PriceBreakdownResponse from(PriceBreakdown bd) {
    return new PriceBreakdownResponse(
        bd.subtotal().amountMinor(),
        bd.discount().amountMinor(),
        bd.serviceCharge().amountMinor(),
        bd.tax().amountMinor(),
        bd.grandTotal().amountMinor(),
        bd.grandTotal().currency().getCurrencyCode(),
        bd.usesIllustrativeRules());
  }
}
