package id.co.nativeapp.loyalty.earnrule.service;

import id.co.nativeapp.loyalty.earnrule.domain.EarnRule;
import id.co.nativeapp.loyalty.earnrule.domain.EarnRuleValidationException;
import id.co.nativeapp.loyalty.earnrule.domain.RuleProvenance;
import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleCreateRequest;
import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleResponse;
import id.co.nativeapp.loyalty.earnrule.repository.EarnRuleRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for earn-rule creation — the {@code
 * PromotionAdminWriter} pattern: a distinct bean so the tx advice and the RLS auto-apply aspect
 * engage through the Spring proxy.
 */
@Component
public class EarnRuleWriter {

  private final EarnRuleRepository repository;

  public EarnRuleWriter(EarnRuleRepository repository) {
    this.repository = repository;
  }

  /**
   * Creates an earn rule, in its own transaction.
   *
   * @throws EarnRuleValidationException if {@code provenance} is not a recognised value
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public EarnRuleResponse create(EarnRuleCreateRequest request) {
    RuleProvenance provenance = parseProvenance(request.provenance());
    LocalDate effectiveFrom =
        request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom();

    // A fresh rule_version stamp: the resolver picks the highest lexicographic rule_version among
    // active, effective rows, so a NEW rule must sort after every prior one — an ISO-instant prefix
    // sorts (approximately) chronologically; the random suffix guarantees uniqueness even for two
    // rules created within the same clock tick (UNIQUE(company_id, rule_version) is the backstop).
    String ruleVersion =
        "COMPANY-" + Instant.now() + "-" + UUID.randomUUID().toString().substring(0, 8);

    EarnRule rule =
        new EarnRule(
            ruleVersion,
            request.pointsPerMinorBp(),
            request.minSaleMinor(),
            provenance,
            request.sourceNote(),
            effectiveFrom,
            request.effectiveTo());
    rule.setCompanyId(TenantContext.require().companyId());
    return toResponse(repository.saveAndFlush(rule));
  }

  private static RuleProvenance parseProvenance(String raw) {
    try {
      return RuleProvenance.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      throw new EarnRuleValidationException(
          "Unknown provenance: " + raw + " — supported: ILLUSTRATIVE_PLACEHOLDER, OFFICIAL");
    }
  }

  static EarnRuleResponse toResponse(EarnRule rule) {
    return new EarnRuleResponse(
        rule.getId(),
        rule.getRuleVersion(),
        rule.getPointsPerMinorBp(),
        rule.getMinSaleMinor(),
        rule.getProvenance().name(),
        rule.getSourceNote(),
        rule.getEffectiveFrom(),
        rule.getEffectiveTo(),
        rule.isActive());
  }
}
