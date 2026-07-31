package id.co.nativeapp.carwash.pricing.dto;

import java.time.LocalDate;

/**
 * {@code GET /api/v1/carwash/pricing/effective-rules} response — the tax + service-charge rules
 * effective TODAY (UTC), resolved the same way {@link
 * id.co.nativeapp.carwash.pricing.service.TaxChargeService TaxChargeService} resolves them at
 * checkout (Phase 5 offline mode, ADR 0028): a POS client caches this to compute PROVISIONAL
 * pricing while offline; the server always re-prices at checkout/replay, so this is a preview only.
 *
 * <p><strong>No-rule fall-through</strong> (mirrors {@code TaxChargeService}): a rule family with
 * no seeded row resolves to {@code 0} basis points and {@code null} version/provenance. {@code
 * currency} is the resolved tax rule's currency, falling back to the resolved service-charge
 * rule's currency, or {@code null} if neither rule is seeded.
 *
 * @param currency the resolved rule(s)' ISO-4217 currency, or {@code null} if no rule is seeded
 * @param asOf the resolution date (today, UTC)
 * @param taxBp the {@code VAT_CARWASH} rate in basis points, {@code 0} if unseeded
 * @param taxRuleVersion the resolved {@code VAT_CARWASH} rule's version label, or {@code null}
 * @param taxProvenance the resolved {@code VAT_CARWASH} rule's provenance ({@code
 *     ILLUSTRATIVE_PLACEHOLDER}/{@code OFFICIAL}), or {@code null}
 * @param serviceChargeBp the {@code SERVICE_CHARGE} rate in basis points, {@code 0} if unseeded
 * @param serviceChargeInTaxBase whether the tax base includes the service charge — governed by the
 *     resolved TAX rule, exactly like {@code TaxChargeService.resolve}; defaults to {@code true}
 *     when no tax rule is seeded
 * @param serviceChargeRuleVersion the resolved {@code SERVICE_CHARGE} rule's version label, or
 *     {@code null}
 * @param serviceChargeProvenance the resolved {@code SERVICE_CHARGE} rule's provenance, or {@code
 *     null}
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
