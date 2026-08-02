package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.dto.OverrideStatutoryRuleRequest;
import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.employee.payroll.dto.SeedOfficialResponse;
import id.co.nativeapp.employee.payroll.dto.StatutoryRuleDetailResponse;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the console's payroll bootstrap + statutory-rule administration (Track P phases
 * P1/P2): delegates to the existing idempotent {@link IllustrativeStatutorySeedWriter} (which owns
 * the tx + RLS GUC and warns loudly — the V3 banner and the drift test remain the source of truth
 * for the figures), the new {@link OfficialStatutorySeedWriter} (ADR 0031's canned OFFICIAL dataset
 * activation), and the new {@link StatutoryRuleOverrideWriter} (the human-verification override /
 * TER-activation path) — each a separate {@code *Writer} bean so the tx proxy + RLS aspect engage
 * (this class itself carries no {@code @Transactional}).
 */
@Service
public class PayrollSetupService {

  private final IllustrativeStatutorySeedWriter seedWriter;
  private final OfficialStatutorySeedWriter officialSeedWriter;
  private final StatutoryRuleOverrideWriter overrideWriter;
  private final PayrollSetupReader reader;

  public PayrollSetupService(
      IllustrativeStatutorySeedWriter seedWriter,
      OfficialStatutorySeedWriter officialSeedWriter,
      StatutoryRuleOverrideWriter overrideWriter,
      PayrollSetupReader reader) {
    this.seedWriter = seedWriter;
    this.officialSeedWriter = officialSeedWriter;
    this.overrideWriter = overrideWriter;
    this.reader = reader;
  }

  /** Seeds the ILLUSTRATIVE PLACEHOLDER catalog + rules for the bound tenant (idempotent). */
  public PayrollSetupResponse seedIllustrative(String baseCurrency) {
    TenantContext.require();
    seedWriter.seed(baseCurrency);
    return reader.status();
  }

  /** Activates a canned OFFICIAL statutory dataset for the bound tenant (idempotent). */
  public SeedOfficialResponse seedOfficial(String datasetVersion) {
    TenantContext.require();
    return officialSeedWriter.seed(datasetVersion);
  }

  /** Overrides a rule_key's currently-open row with a new effective-dated one. */
  public StatutoryRuleDetailResponse overrideRule(
      String ruleKey, OverrideStatutoryRuleRequest request) {
    TenantContext.require();
    return overrideWriter.override(
        ruleKey,
        request.paramsJson(),
        request.effectiveFrom(),
        request.sourceNote(),
        request.provenance());
  }
}
