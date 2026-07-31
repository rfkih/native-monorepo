package id.co.nativeapp.barbershop.pricing.service;

import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.pricing.domain.RuleProvenance;
import id.co.nativeapp.barbershop.pricing.domain.TaxChargeRule;
import id.co.nativeapp.barbershop.pricing.dto.EffectiveRulesResponse;
import id.co.nativeapp.barbershop.pricing.projection.TaxChargeRuleView;
import id.co.nativeapp.barbershop.pricing.repository.TaxChargeRuleRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the effective tax and service-charge rules at the ticket's {@code occurredAt} date and
 * applies the pricing formula to produce a {@link PriceBreakdown} — ported from carwash-service's
 * {@code pricing} feature (Phase 2 pricing pattern), keyed on {@link TaxChargeRule#KEY_VAT} ({@code
 * VAT_BARBERSHOP}).
 *
 * <p><strong>Pricing formula (round ONCE per component via {@link
 * Money#applyBasisPoints}):</strong>
 *
 * <ol>
 *   <li>{@code subtotal} = Σ line totals (caller pre-computed)
 *   <li>{@code discount} = percent ({@code subtotal.applyBasisPoints(discountBp)}) OR fixed Money;
 *       clamped to {@code discount.min(subtotal)} so discount ≤ subtotal always holds
 *   <li>{@code taxableBase = subtotal − discount}
 *   <li>{@code serviceCharge = taxableBase.applyBasisPoints(serviceChargeBp)}
 *   <li>{@code taxBase = taxableBase [+ serviceCharge if serviceChargeInTaxBase]}
 *   <li>{@code tax = taxBase.applyBasisPoints(taxBp)}
 *   <li>{@code grandTotal = taxableBase + serviceCharge + tax}
 * </ol>
 *
 * <p>The reconciliation identity is asserted in {@link PriceBreakdown}'s constructor. A {@code
 * grandTotal ≤ 0} is rejected by the caller as an {@link IllegalArgumentException} → 400.
 *
 * <p><strong>Illustrative-rules flag:</strong> if ANY resolved rule carries {@code provenance =
 * ILLUSTRATIVE_PLACEHOLDER}, the returned breakdown sets {@code usesIllustrativeRules = true} and
 * this service logs a loud warning. The flag propagates to {@code SaleRecorded} so finance can
 * badge derived GL entries as provisional.
 *
 * <p><strong>No-rule fall-through:</strong> if no tax rule is seeded for this tenant (possible in
 * early onboarding, and the barbershop V1 baseline seeds NO {@code SERVICE_CHARGE} row at all), the
 * tax is zero (a zero-rate company). Same for service charge. The checkout proceeds; {@code
 * usesIllustrativeRules} is set only when a rule IS resolved and it is illustrative.
 *
 * <p>Not {@code @Transactional} itself — it is always called from inside the ticket checkout's
 * {@code REQUIRES_NEW} transaction which already has the tenant GUC set (the RLS auto-apply aspect
 * fires on the surrounding transactional bean).
 */
@Service
public class TaxChargeService {

  private static final Logger log = LoggerFactory.getLogger(TaxChargeService.class);

  private final TaxChargeRuleRepository repository;

  public TaxChargeService(TaxChargeRuleRepository repository) {
    this.repository = repository;
  }

  /**
   * Computes the price breakdown for a checkout.
   *
   * @param subtotal the pre-computed order subtotal (Σ line totals)
   * @param discountBp basis-point percent discount (0 = no discount); applied on subtotal
   * @param fixedDiscount optional fixed Money discount to use INSTEAD of a percent discount (when
   *     non-null, overrides {@code discountBp}); must be in the same currency as subtotal
   * @param occurredAt the ticket's occurred-at instant (determines the effective rule window)
   * @return the fully computed {@link PriceBreakdown}
   */
  public PriceBreakdown resolve(
      Money subtotal, long discountBp, Money fixedDiscount, java.time.Instant occurredAt) {

    LocalDate asOf = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
    boolean usesIllustrative = false;
    String taxRuleVersion = null;

    // ------------------------------------------------------------------
    // 1. Resolve tax rule (VAT_BARBERSHOP).
    // ------------------------------------------------------------------
    Optional<TaxChargeRuleView> taxRuleOpt = repository.findEffective(TaxChargeRule.KEY_VAT, asOf);
    long taxBp = 0L;
    boolean serviceChargeInTaxBase = true; // safe default when no service-charge rule
    if (taxRuleOpt.isPresent()) {
      TaxChargeRuleView tv = taxRuleOpt.get();
      taxBp = tv.getRateBp();
      serviceChargeInTaxBase = tv.isServiceChargeInTaxBase();
      taxRuleVersion = tv.getRuleVersion();
      if (RuleProvenance.ILLUSTRATIVE_PLACEHOLDER.name().equals(tv.getProvenance())) {
        usesIllustrative = true;
        log.warn(
            "ILLUSTRATIVE TAX RULE resolved for checkout: ruleKey={} ruleVersion={} rateBp={}"
                + " — this is NOT a verified Indonesian tax rate (the barbershop VAT/regional-"
                + "service-tax regime and rate are SME-gated). Do NOT use this figure for"
                + " regulatory reporting.",
            TaxChargeRule.KEY_VAT,
            tv.getRuleVersion(),
            taxBp);
      }
    }

    // ------------------------------------------------------------------
    // 2. Resolve service-charge rule (SERVICE_CHARGE).
    // ------------------------------------------------------------------
    Optional<TaxChargeRuleView> scRuleOpt =
        repository.findEffective(TaxChargeRule.KEY_SERVICE_CHARGE, asOf);
    long serviceChargeBp = 0L;
    if (scRuleOpt.isPresent()) {
      TaxChargeRuleView sv = scRuleOpt.get();
      serviceChargeBp = sv.getRateBp();
      // service_charge_in_tax_base is governed by the VAT rule (the tax rule defines the tax base
      // composition); reading it again from the SC rule would be redundant.
      if (RuleProvenance.ILLUSTRATIVE_PLACEHOLDER.name().equals(sv.getProvenance())) {
        usesIllustrative = true;
        log.warn(
            "ILLUSTRATIVE SERVICE-CHARGE RULE resolved: ruleKey={} ruleVersion={} rateBp={}"
                + " — not verified by an accounting SME. Service-charge-as-revenue vs."
                + " tip-liability treatment is SME-gated.",
            TaxChargeRule.KEY_SERVICE_CHARGE,
            sv.getRuleVersion(),
            serviceChargeBp);
      }
    }

    // ------------------------------------------------------------------
    // 3. Apply pricing formula (round ONCE per component).
    // ------------------------------------------------------------------
    // 3a. Discount — percent OR fixed, clamped to ≤ subtotal.
    Money discount;
    if (fixedDiscount != null) {
      discount = fixedDiscount.min(subtotal);
    } else {
      discount = subtotal.applyBasisPoints(discountBp).min(subtotal);
    }

    // 3b. Taxable base.
    Money taxableBase = subtotal.minus(discount);

    // 3c. Service charge (on taxable base).
    Money serviceCharge = taxableBase.applyBasisPoints(serviceChargeBp);

    // 3d. Tax base: taxableBase [+ serviceCharge if config flag].
    Money taxBase = serviceChargeInTaxBase ? taxableBase.plus(serviceCharge) : taxableBase;

    // 3e. Tax.
    Money tax = taxBase.applyBasisPoints(taxBp);

    // 3f. Grand total.
    Money grandTotal = taxableBase.plus(serviceCharge).plus(tax);

    return new PriceBreakdown(
        subtotal,
        discount,
        taxableBase,
        serviceCharge,
        tax,
        grandTotal,
        taxRuleVersion,
        usesIllustrative);
  }

  /**
   * Phase 5 (ADR 0028): resolves TODAY's (UTC) effective {@code VAT_BARBERSHOP}/{@code
   * SERVICE_CHARGE} rules for the offline POS's provisional-pricing snapshot — the same rule keys
   * {@link #resolve} uses, with the same no-rule fall-through (rate {@code 0}, version/provenance
   * {@code null}). Read-only; unlike {@link #resolve} (always called from inside an existing {@code
   * REQUIRES_NEW} checkout transaction), this is a standalone GET so it opens its own transaction
   * to engage the RLS tenant GUC.
   */
  @Transactional(readOnly = true)
  public EffectiveRulesResponse resolveEffectiveRules() {
    TenantContext.require();
    LocalDate asOf = LocalDate.now(ZoneOffset.UTC);

    Optional<TaxChargeRuleView> taxRuleOpt = repository.findEffective(TaxChargeRule.KEY_VAT, asOf);
    Optional<TaxChargeRuleView> scRuleOpt =
        repository.findEffective(TaxChargeRule.KEY_SERVICE_CHARGE, asOf);

    long taxBp = taxRuleOpt.map(TaxChargeRuleView::getRateBp).orElse(0L);
    String taxRuleVersion = taxRuleOpt.map(TaxChargeRuleView::getRuleVersion).orElse(null);
    String taxProvenance = taxRuleOpt.map(TaxChargeRuleView::getProvenance).orElse(null);
    // service_charge_in_tax_base is governed by the VAT rule, mirroring resolve() step 1 — the
    // safe default (true) applies only when no VAT rule is seeded.
    boolean serviceChargeInTaxBase =
        taxRuleOpt.map(TaxChargeRuleView::isServiceChargeInTaxBase).orElse(true);

    long serviceChargeBp = scRuleOpt.map(TaxChargeRuleView::getRateBp).orElse(0L);
    String serviceChargeRuleVersion = scRuleOpt.map(TaxChargeRuleView::getRuleVersion).orElse(null);
    String serviceChargeProvenance = scRuleOpt.map(TaxChargeRuleView::getProvenance).orElse(null);

    String currency =
        taxRuleOpt
            .map(v -> v.getCurrency().strip())
            .or(() -> scRuleOpt.map(v -> v.getCurrency().strip()))
            .orElse(null);

    return new EffectiveRulesResponse(
        currency,
        asOf,
        taxBp,
        taxRuleVersion,
        taxProvenance,
        serviceChargeBp,
        serviceChargeInTaxBase,
        serviceChargeRuleVersion,
        serviceChargeProvenance);
  }
}
