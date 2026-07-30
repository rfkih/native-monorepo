package id.co.nativeapp.barbershop.pricing.projection;

/**
 * Native-query read projection for a resolved {@code tax_charge_rule} row (ported from
 * carwash-service's {@code pricing} feature).
 *
 * <p>The read path selects only the columns the pricing pipeline needs (rule, version, rate) —
 * never {@code SELECT *} of the entity (CODE-STRUCTURE.md §3.3). The accessor names map snake_case
 * SQL column aliases to camelCase via Spring Data's projection interface convention.
 */
public interface TaxChargeRuleView {

  String getRuleKey();

  String getRuleVersion();

  long getRateBp();

  boolean isServiceChargeInTaxBase();

  String getProvenance();
}
