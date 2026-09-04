package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.dto.OverrideStatutoryRuleRequest;
import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.employee.payroll.dto.SeedOfficialResponse;
import id.co.nativeapp.employee.payroll.dto.StatutoryRuleDetailResponse;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the console's payroll bootstrap + statutory-rule administration (Track P phases
 * P1/P2): delegates to the existing idempotent {@link IllustrativeStatutorySeedWriter} (which owns
 * the tx + RLS GUC and warns loudly — the V3 banner and the drift test remain the source of truth
 * for the figures), the new {@link OfficialStatutorySeedWriter} (ADR 0031's canned OFFICIAL dataset
 * activation), and the new {@link StatutoryRuleOverrideWriter} (the human-verification override /
 * TER-activation path) — each a separate {@code *Writer} bean so the tx proxy + RLS aspect engage.
 * {@link #seedOfficialBootstrap} (ADR 0042's go-live default) is the one method on this class that
 * itself carries {@code @Transactional}: it composes two writer calls that must commit or roll back
 * together, so the orchestration itself needs a transaction boundary (still delegating the actual
 * writes to the same two writer beans, never duplicating their logic).
 */
@Service
public class PayrollSetupService {

  /**
   * The OFFICIAL dataset a fresh tenant activates by default (ADR 0042 go-live). The single source
   * of truth shared by the console setup-gate ({@code
   * PayrollSetupController#seedOfficialBootstrap}) and the automatic {@code CompanyCreated}
   * bootstrap ({@code PayrollBootstrapWriter}).
   */
  public static final String DEFAULT_OFFICIAL_DATASET_VERSION = "ID-2026.1";

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

  /**
   * Bootstraps a FRESH tenant straight to a fully OFFICIAL statutory setup (ADR 0042 go-live
   * default) — one call, one transaction. First runs {@link IllustrativeStatutorySeedWriter#seed}
   * to bootstrap the tenant's base pay-component catalog in {@code baseCurrency} (idempotent — it
   * is also the ONLY writer that creates that catalog, so this step is not optional), then
   * immediately {@link OfficialStatutorySeedWriter#seed activates} {@code datasetVersion} over it,
   * which supersedes every illustrative rule sharing a {@code rule_key} and deactivates the orphan
   * {@code PPH21_PROGRESSIVE} rule (its class javadoc). Net DB state: fully OFFICIAL, no active
   * illustrative rule — a subsequent payroll run resolves {@code uses_illustrative_rules = false}.
   * Both writer calls run inside this single transaction, so a failure partway through leaves no
   * half-seeded tenant.
   */
  @Transactional
  public SeedOfficialResponse seedOfficialBootstrap(String baseCurrency, String datasetVersion) {
    TenantContext.require();
    seedWriter.seed(baseCurrency);
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
