package id.co.nativeapp.restaurant.pricing.dto;

import java.time.LocalDate;

/**
 * Response body for {@code GET /api/v1/pricing/effective-rules} — the Phase 5 (ADR 0028) offline
 * POS provisional-pricing snapshot: TODAY's (UTC) effective tax and service-charge basis-point
 * rates plus their rule version/provenance, so the client can price a queued offline sale from a
 * TTL-cached snapshot using the SAME formula the server applies on replay (mirroring {@code
 * PricingFormula}/{@code TaxChargeService}, including HALF_EVEN rounding).
 *
 * <p><strong>No-rule fall-through</strong> mirrors {@code TaxChargeService.resolve}: when no {@code
 * tax_charge_rule} is seeded for a key, its {@code *Bp} is {@code 0} and its {@code
 * *RuleVersion}/{@code *Provenance} are {@code null} — never an error.
 *
 * @param currency ISO-4217 code of the resolved rule(s) (the tax rule's currency, falling back to
 *     the service-charge rule's), or {@code null} when neither rule is seeded
 * @param asOf the date the rules were resolved at (today, UTC)
 * @param taxBp the effective PB1/tax rate in basis points; {@code 0} if no rule is seeded
 * @param taxRuleVersion the resolved tax rule's version label, or {@code null}
 * @param taxProvenance {@code "ILLUSTRATIVE_PLACEHOLDER"} or {@code "OFFICIAL"}, or {@code null}
 * @param serviceChargeBp the effective service-charge rate in basis points; {@code 0} if no rule is
 *     seeded
 * @param serviceChargeInTaxBase whether the service charge is included in the tax base (governed by
 *     the tax rule; defaults {@code true} when no tax rule is seeded, mirroring {@code
 *     TaxChargeService})
 * @param serviceChargeRuleVersion the resolved service-charge rule's version label, or {@code null}
 * @param serviceChargeProvenance {@code "ILLUSTRATIVE_PLACEHOLDER"} or {@code "OFFICIAL"}, or
 *     {@code null}
 */
public record EffectiveRulesResponse(
    String currency,
    LocalDate asOf,
    long taxBp,
    String taxRuleVersion,
    String taxProvenance,
    long serviceChargeBp,
    boolean serviceChargeInTaxBase,
    String serviceChargeRuleVersion,
    String serviceChargeProvenance) {}
