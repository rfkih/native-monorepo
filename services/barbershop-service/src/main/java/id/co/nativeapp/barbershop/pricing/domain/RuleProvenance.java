package id.co.nativeapp.barbershop.pricing.domain;

/**
 * The provenance of a {@link TaxChargeRule}'s figures — mirrors the HR-9 three-layer illustrative
 * flagging from {@code employee-service} (and the carwash-service {@code pricing} feature this
 * package is ported from). A rule is either an {@code ILLUSTRATIVE_PLACEHOLDER}
 * (deliberately-illustrative numbers seeded for development — NOT verified Indonesian tax-law
 * rates) or {@code OFFICIAL} (verified statutory figures supplied by an accounting/tax SME).
 *
 * <p>The column is {@code NOT NULL} with a CHECK constraint and the enum has NO default, so a new
 * rule row must declare its provenance explicitly and a placeholder can never be silently treated
 * as official. A checkout resolving ANY {@code ILLUSTRATIVE_PLACEHOLDER} rule is flagged {@code
 * uses_illustrative_rules = true} and warns loudly in the emitted {@code SaleRecorded} event.
 *
 * <p><strong>SME NOTE: the barbershop tax/service-charge regime is ILLUSTRATIVE only.</strong>
 * Whether a barbershop / personal-care sale is subject to PPN (national VAT, currently 11%), a
 * regional service tax, or both, and whether a service charge applies at all, is SME-gated — an
 * accounting + tax SME must confirm the correct regime, rate(s), and tax-base composition before
 * any seeded value is used for regulatory reporting. NEVER deploy the seeded values as fact.
 */
public enum RuleProvenance {
  ILLUSTRATIVE_PLACEHOLDER,
  OFFICIAL
}
