package id.co.nativeapp.restaurant.pricing.projection;

/**
 * Native-query read projection for a resolved {@code tax_charge_rule} row.
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
   * ISO-4217 currency this rule applies to (CHAR(3), right-padded — callers {@code .strip()} it).
   * Added for the Phase 5 (ADR 0028) {@code GET /api/v1/pricing/effective-rules} snapshot, which has
   * no other source for the resolved rule's currency.
   */
  String getCurrency();
}
