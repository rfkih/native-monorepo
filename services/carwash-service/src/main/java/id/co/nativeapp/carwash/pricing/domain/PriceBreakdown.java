package id.co.nativeapp.carwash.pricing.domain;

import id.co.nativeapp.money.Money;
import java.util.Objects;

/**
 * Immutable value object holding the price breakdown computed by the pricing service for one
 * carwash checkout (ported from restaurant-service's {@code pricing} feature — the SME note below
 * is carwash-specific).
 *
 * <p>All amounts are {@link Money} (integer minor units + ISO-4217 currency — rule 8, never a
 * float). The reconciliation identity holds by construction:
 *
 * <pre>
 *   subtotal − discount + serviceCharge + tax == grandTotal
 * </pre>
 *
 * <p>The {@code usesIllustrativeRules} flag is set when ANY resolved {@code tax_charge_rule} row
 * carried {@code provenance = ILLUSTRATIVE_PLACEHOLDER}. It propagates to the {@code SaleRecorded}
 * Avro event so finance can badge the derived GL entries as provisional.
 *
 * <p><strong>SME NOTE: the carwash tax regime is ILLUSTRATIVE only</strong> — whether a car-wash
 * sale is subject to PPN (national VAT) or a regional service/entertainment tax, and whether a
 * service charge applies, is not yet confirmed by an accounting + tax SME (see {@link
 * RuleProvenance}).
 *
 * @param subtotal sum of line totals (before any discount or tax)
 * @param discount order-level discount (clamped to ≤ subtotal)
 * @param taxableBase subtotal − discount
 * @param serviceCharge service charge amount (applied on the taxable base)
 * @param tax tax amount (applied on taxBase = taxableBase [+ serviceCharge if configured])
 * @param grandTotal the amount the customer pays: taxableBase + serviceCharge + tax
 * @param taxRuleVersion the rule_version label of the resolved VAT/tax rule, or {@code null} if no
 *     tax rule is seeded (illustrative only; stamped on the event for traceability)
 * @param usesIllustrativeRules {@code true} when any resolved rule was {@code
 *     ILLUSTRATIVE_PLACEHOLDER}
 */
public record PriceBreakdown(
    Money subtotal,
    Money discount,
    Money taxableBase,
    Money serviceCharge,
    Money tax,
    Money grandTotal,
    String taxRuleVersion,
    boolean usesIllustrativeRules) {

  public PriceBreakdown {
    Objects.requireNonNull(subtotal, "subtotal");
    Objects.requireNonNull(discount, "discount");
    Objects.requireNonNull(taxableBase, "taxableBase");
    Objects.requireNonNull(serviceCharge, "serviceCharge");
    Objects.requireNonNull(tax, "tax");
    Objects.requireNonNull(grandTotal, "grandTotal");
    // Reconciliation identity: subtotal − discount + serviceCharge + tax == grandTotal
    Money expected = subtotal.minus(discount).plus(serviceCharge).plus(tax);
    if (!expected.equals(grandTotal)) {
      throw new IllegalArgumentException(
          "PriceBreakdown reconciliation violation: subtotal("
              + subtotal.amountMinor()
              + ") - discount("
              + discount.amountMinor()
              + ") + serviceCharge("
              + serviceCharge.amountMinor()
              + ") + tax("
              + tax.amountMinor()
              + ") = "
              + expected.amountMinor()
              + " != grandTotal("
              + grandTotal.amountMinor()
              + ")");
    }
  }
}
