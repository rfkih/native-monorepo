package id.co.nativeapp.carwash.pricing.projection;

/**
 * Native-query read projection for a resolved {@code tax_charge_rule} row (ported from
 * restaurant-service's {@code pricing} feature).
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

  /**
   * The rule's ISO-4217 currency ({@code CHAR(3)} — may carry trailing spaces; callers must {@link
   * String#strip()} it). Selected for the {@code pricing.effective-rules} preview endpoint (Phase
   * 5, ADR 0028); {@link id.co.nativeapp.carwash.pricing.service.TaxChargeService TaxChargeService}
   * does not read it.
   */
  String getCurrency();
}
