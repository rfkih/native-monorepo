package id.co.nativeapp.loyalty.earnrule.domain;

/**
 * The provenance of an {@link EarnRule}'s figures — mirrors the HR-9 three-layer illustrative
 * flagging established by {@code tax_charge_rule} across the verticals (restaurant/carwash/
 * barbershop) and {@code statutory_rule} in employee-service. A rule is either an {@code
 * ILLUSTRATIVE_PLACEHOLDER} (deliberately-illustrative figures) or {@code OFFICIAL} (verified,
 * company-approved figures).
 *
 * <p>The column is {@code NOT NULL} with a CHECK constraint and the enum has NO default, so a new
 * rule row must declare its provenance explicitly.
 */
public enum RuleProvenance {
  ILLUSTRATIVE_PLACEHOLDER,
  OFFICIAL
}
