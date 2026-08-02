package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.RuleProvenance;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.domain.UnknownStatutoryRuleException;
import id.co.nativeapp.employee.payroll.dto.StatutoryRuleDetailResponse;
import id.co.nativeapp.employee.payroll.repository.StatutoryRuleRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code PATCH /api/v1/payroll-setup/rules/{ruleKey}} — the human-verification override path (ADR
 * 0031's activation checklist: a person transcribes/verifies a rule's real figures, then PATCHes
 * them in). This mechanism is how {@code PPH21_TER} was flipped from {@code
 * ILLUSTRATIVE_PLACEHOLDER} to {@code OFFICIAL} once its PMK 168/2023 Lampiran band tables were
 * transcribed + cross-verified (ID-2026.1 ships every rule OFFICIAL as a result — no rule is
 * illustrative any more), and it is the SAME path a periodic re-verification uses going forward
 * (e.g. {@code BPJS_JP}'s ceiling, adjusted every March — OFFICIAL-to-OFFICIAL, a correction, not a
 * provenance flip). Never mutates a row in place — creates a NEW effective-dated row and supersedes
 * the prior open one ({@link StatutoryRule#supersede}), exactly like {@link
 * OfficialStatutorySeedWriter}, so a run that already froze the OLD rule stays byte-identically
 * reproducible (HR-7).
 */
@Component
public class StatutoryRuleOverrideWriter {

  private static final Logger log = LoggerFactory.getLogger(StatutoryRuleOverrideWriter.class);

  private final StatutoryRuleRepository statutoryRuleRepository;

  public StatutoryRuleOverrideWriter(StatutoryRuleRepository statutoryRuleRepository) {
    this.statutoryRuleRepository = statutoryRuleRepository;
  }

  /**
   * Overrides {@code ruleKey}'s currently-open row with a new effective-dated one.
   *
   * @throws UnknownStatutoryRuleException (→ 404) if {@code ruleKey} has no currently-open row
   * @throws IllegalArgumentException (→ 400) if {@code effectiveFrom} is not strictly after the
   *     current row's {@code effectiveFrom}, or {@code paramsJson} fails the current row's
   *     calc-type family parser
   */
  @Transactional
  public StatutoryRuleDetailResponse override(
      String ruleKey,
      String paramsJson,
      LocalDate effectiveFrom,
      String sourceNote,
      RuleProvenance provenance) {
    String tenant = TenantContext.require().companyId();
    StatutoryRule current =
        statutoryRuleRepository
            .findByRuleKeyAndActiveTrueAndEffectiveTo(ruleKey, StatutoryRule.OPEN_ENDED)
            .orElseThrow(() -> new UnknownStatutoryRuleException(ruleKey));

    if (!effectiveFrom.isAfter(current.getEffectiveFrom())) {
      throw new IllegalArgumentException(
          "effectiveFrom "
              + effectiveFrom
              + " must be strictly after the current rule's effectiveFrom "
              + current.getEffectiveFrom());
    }
    // Fail loud on a malformed override BEFORE writing anything — same calc family as the row being
    // superseded (an override tunes figures/provenance/effective date, never the algorithm family).
    StatutoryParams.validate(current.getCalcType(), paramsJson);

    current.supersede(effectiveFrom);
    statutoryRuleRepository.save(current);

    String newRuleVersion = current.getRuleVersion() + "-OVERRIDE-" + effectiveFrom;
    StatutoryRule fresh =
        new StatutoryRule(
            ruleKey,
            newRuleVersion,
            current.getCalcType(),
            paramsJson,
            current.getCurrency(),
            provenance,
            sourceNote,
            effectiveFrom,
            StatutoryRule.OPEN_ENDED);
    fresh.setCompanyId(tenant);
    statutoryRuleRepository.save(fresh);

    log.info(
        "statutory_rule {} overridden for tenant {}: {} {} -> {} {} effective {}",
        ruleKey,
        tenant,
        current.getRuleVersion(),
        current.getProvenance(),
        newRuleVersion,
        provenance,
        effectiveFrom);

    return new StatutoryRuleDetailResponse(
        fresh.getRuleKey(),
        fresh.getRuleVersion(),
        fresh.getCalcType().name(),
        fresh.getParamsJson(),
        fresh.getCurrency(),
        fresh.getProvenance().name(),
        fresh.getSourceNote(),
        fresh.getEffectiveFrom(),
        fresh.getEffectiveTo());
  }
}
