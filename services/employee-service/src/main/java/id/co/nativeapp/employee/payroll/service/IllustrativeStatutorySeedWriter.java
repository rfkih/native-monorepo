package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.CalcType;
import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.RuleProvenance;
import id.co.nativeapp.employee.payroll.domain.StatutoryCalcType;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.repository.PayComponentRepository;
import id.co.nativeapp.employee.payroll.repository.StatutoryRuleRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a tenant's DEFAULT pay-component catalog + statutory rules with <strong>ILLUSTRATIVE
 * PLACEHOLDER</strong> figures (HR-9). Because every payroll table is under FORCE ROW LEVEL
 * SECURITY (rule 5), seeded rows must be written inside a bound tenant scope so the {@code WITH
 * CHECK} passes — a plain Flyway {@code INSERT} (which runs with no tenant GUC) cannot satisfy the
 * policy. So the loud-banner seed is applied per-tenant at runtime through this proxied
 * {@code @Transactional} writer (the auto-RLS aspect sets the GUC), NOT in the V3 migration body
 * (see {@code V3__seed_illustrative_statutory_rules.sql}, which is the documented source of the
 * figures).
 *
 * <p><strong>ILLUSTRATIVE PLACEHOLDER FIGURES ONLY — NOT verified DJP/BPJS rates. DO NOT USE FOR
 * REAL PAYROLL.</strong> Replace with OFFICIAL effective-dated rows (provenance=OFFICIAL, a new
 * rule_version) before production. The numbers below are deliberately round, obviously-fake
 * illustrative values (a single 10%/15% bracket, ceiling 10,000,000, flat PTKP) so no one mistakes
 * them for the real schedule. A run resolving any of these rows is flagged {@code
 * uses_illustrative_rules = true} and warns loudly.
 */
@Component
public class IllustrativeStatutorySeedWriter {

  private static final Logger log = LoggerFactory.getLogger(IllustrativeStatutorySeedWriter.class);

  /** The immutable illustrative version label stamped on every seeded rule + payslip line. */
  public static final String ILLUSTRATIVE_VERSION = "ILLUSTRATIVE-2026.1";

  private static final String DISCLAIMER =
      "ILLUSTRATIVE PLACEHOLDER — NOT verified DJP/BPJS rates. DO NOT USE FOR REAL PAYROLL. Replace"
          + " with OFFICIAL effective-dated rows before production.";

  private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);

  private final PayComponentRepository payComponentRepository;
  private final StatutoryRuleRepository statutoryRuleRepository;

  public IllustrativeStatutorySeedWriter(
      PayComponentRepository payComponentRepository,
      StatutoryRuleRepository statutoryRuleRepository) {
    this.payComponentRepository = payComponentRepository;
    this.statutoryRuleRepository = statutoryRuleRepository;
  }

  /**
   * Seeds the bound tenant's default catalog + illustrative statutory rules (idempotent: skips if a
   * BASE component already exists). Must be called inside a bound {@link TenantContext} scope.
   *
   * @param currency the tenant's base ISO-4217 currency (the seeded figures are in it)
   */
  @Transactional
  public void seed(String currency) {
    String tenant = TenantContext.require().companyId();
    if (payComponentRepository.findByComponentKey("BASE").isPresent()) {
      return; // already seeded for this tenant
    }
    log.warn(
        "Seeding tenant {} with ILLUSTRATIVE PLACEHOLDER statutory figures ({}) — NOT verified"
            + " DJP/BPJS rates; replace with OFFICIAL rows before production",
        tenant,
        ILLUSTRATIVE_VERSION);

    seedComponents(tenant);
    seedRules(tenant, currency);
  }

  private void seedComponents(String tenant) {
    // BASE earning (taxable), the labor-cost GL anchor.
    save(
        new PayComponent(
            "BASE",
            PayComponentKind.EARNING,
            CalcType.FIXED,
            PayComponentBearer.EMPLOYEE,
            "5100-SALARY",
            true,
            null,
            0),
        tenant);
    // A non-statutory allowance (taxable).
    save(
        new PayComponent(
            "MEAL_ALLOWANCE",
            PayComponentKind.EARNING,
            CalcType.FIXED,
            PayComponentBearer.EMPLOYEE,
            "5110-ALLOWANCE",
            true,
            null,
            10),
        tenant);
    // Social insurance (BPJS-shaped) — employee leg (deduction, reduces tax base) + employer leg.
    save(
        new PayComponent(
            "BPJS_KES_EE",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYEE,
            "2100-BPJS",
            false,
            "BPJS_KESEHATAN",
            20),
        tenant);
    save(
        new PayComponent(
            "BPJS_KES_ER",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYER,
            "5200-BPJS-ER",
            false,
            "BPJS_KESEHATAN",
            21),
        tenant);
    // Income tax (PPh21-shaped) — employee deduction.
    save(
        new PayComponent(
            "PPH21",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_PROGRESSIVE,
            PayComponentBearer.EMPLOYEE,
            "2110-PPH21",
            false,
            "PPH21_PROGRESSIVE",
            30),
        tenant);
  }

  private void seedRules(String tenant, String currency) {
    // BPJS_KESEHATAN: percentage-with-ceiling. Employee 1% (100 bp), employer 4% (400 bp),
    // ceiling 10,000,000 minor; the employee leg reduces the income-tax base.
    saveRule(
        new StatutoryRule(
            "BPJS_KESEHATAN",
            ILLUSTRATIVE_VERSION,
            StatutoryCalcType.PERCENTAGE_CEILING,
            "{\"ceiling_minor\":10000000,\"employee_bp\":100,\"employer_bp\":400,"
                + "\"reduces_tax_base\":true}",
            currency,
            RuleProvenance.ILLUSTRATIVE_PLACEHOLDER,
            DISCLAIMER,
            EFFECTIVE_FROM,
            StatutoryRule.OPEN_ENDED),
        tenant);

    // PPH21_PROGRESSIVE: two obviously-fake brackets — 10% up to 50,000,000, then 15% above.
    saveRule(
        new StatutoryRule(
            "PPH21_PROGRESSIVE",
            ILLUSTRATIVE_VERSION,
            StatutoryCalcType.PROGRESSIVE_BRACKET,
            "{\"brackets\":["
                + "{\"floor_minor\":0,\"cap_minor\":50000000,\"rate_bp\":1000},"
                + "{\"floor_minor\":50000000,\"cap_minor\":999999999999,\"rate_bp\":1500}]}",
            currency,
            RuleProvenance.ILLUSTRATIVE_PLACEHOLDER,
            DISCLAIMER,
            EFFECTIVE_FROM,
            StatutoryRule.OPEN_ENDED),
        tenant);

    // PTKP_RELIEF: a flat illustrative relief table (annual), annualized over 12 months.
    saveRule(
        new StatutoryRule(
            "PTKP_RELIEF",
            ILLUSTRATIVE_VERSION,
            StatutoryCalcType.RELIEF_TABLE,
            "{\"annualization_months\":12,\"ptkp\":{"
                + "\"TK0\":54000000,\"TK1\":58500000,\"TK2\":63000000,\"TK3\":67500000,"
                + "\"K0\":58500000,\"K1\":63000000,\"K2\":67500000,\"K3\":72000000}}",
            currency,
            RuleProvenance.ILLUSTRATIVE_PLACEHOLDER,
            DISCLAIMER,
            EFFECTIVE_FROM,
            StatutoryRule.OPEN_ENDED),
        tenant);
  }

  private void save(PayComponent component, String tenant) {
    component.setCompanyId(tenant);
    payComponentRepository.save(component);
  }

  private void saveRule(StatutoryRule rule, String tenant) {
    rule.setCompanyId(tenant);
    statutoryRuleRepository.save(rule);
  }

  /**
   * Convenience for tests/seeding a fixed Money figure (kept here so the figure stays in one
   * place).
   */
  public static Money illustrativeMoney(long minor, String currency) {
    return Money.ofMinor(minor, currency);
  }
}
