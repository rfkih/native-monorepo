package id.co.nativeapp.restaurant.pricing.domain;

/**
 * The provenance of a {@link TaxChargeRule}'s figures — mirrors the HR-9 three-layer illustrative
 * flagging from {@code employee-service}. A rule is either an {@code ILLUSTRATIVE_PLACEHOLDER}
 * (deliberately-illustrative numbers seeded for development — NOT verified Indonesian tax-law
 * rates) or {@code OFFICIAL} (verified statutory figures supplied by an accounting/tax SME).
 *
 * <p>The column is {@code NOT NULL} with a CHECK constraint and the enum has NO default, so a new
 * rule row must declare its provenance explicitly and a placeholder can never be silently treated
 * as official. A checkout resolving ANY {@code ILLUSTRATIVE_PLACEHOLDER} rule is flagged {@code
 * uses_illustrative_rules = true} and warns loudly in the emitted {@code SaleRecorded} event.
 *
 * <p><strong>SME NOTE: the seeded PB1 10% rate and 5% service charge are ILLUSTRATIVE
 * only.</strong> The applicable Indonesian tax on restaurant sales may be PB1 (regional
 * entertainment tax, rate varies by municipality), PPN (national VAT, currently 11%), or both — an
 * accounting + tax SME must confirm the correct regime, rate(s), and the composition of the tax
 * base (whether service charge is included). NEVER deploy the seeded values as fact.
 */
public enum RuleProvenance {
  ILLUSTRATIVE_PLACEHOLDER,
  OFFICIAL
}
