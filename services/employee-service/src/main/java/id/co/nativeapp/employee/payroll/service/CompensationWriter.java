package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.domain.EarningRule;
import id.co.nativeapp.employee.payroll.domain.PayFrequency;
import id.co.nativeapp.employee.payroll.repository.CompensationPackageRepository;
import id.co.nativeapp.employee.payroll.repository.EarningRuleRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units that set up an employee's compensation: a {@link
 * CompensationPackage} (base pay PII-encrypted) and its {@link EarningRule}s. A distinct {@code
 * *Writer} bean so the Spring proxy + {@link RlsAutoApplyAspect} engage (rule 5); {@code
 * company_id} is stamped from the bound tenant, never a request. Base pay / fixed earning amounts
 * are PII and never logged here.
 */
@Component
public class CompensationWriter {

  private final CompensationPackageRepository compPackageRepository;
  private final EarningRuleRepository earningRuleRepository;

  public CompensationWriter(
      CompensationPackageRepository compPackageRepository,
      EarningRuleRepository earningRuleRepository) {
    this.compPackageRepository = compPackageRepository;
    this.earningRuleRepository = earningRuleRepository;
  }

  /** Creates a compensation package for an employee (base pay encrypted at rest). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CompensationPackage createPackage(
      UUID employeeId,
      UUID employmentContractId,
      Money basePay,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    String tenant = TenantContext.require().companyId();
    CompensationPackage pkg =
        new CompensationPackage(
            employeeId,
            employmentContractId,
            basePay,
            PayFrequency.MONTHLY,
            effectiveFrom,
            effectiveTo);
    pkg.setCompanyId(tenant);
    CompensationPackage saved = compPackageRepository.save(pkg);
    compPackageRepository.flush();
    return saved;
  }

  /** Adds a fixed-amount earning rule (the amount is PII, encrypted at rest). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public EarningRule addFixedEarning(
      UUID compensationPackageId,
      UUID payComponentId,
      Money amount,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    String tenant = TenantContext.require().companyId();
    EarningRule rule =
        EarningRule.fixedAmount(
            compensationPackageId, payComponentId, amount, effectiveFrom, effectiveTo);
    rule.setCompanyId(tenant);
    return earningRuleRepository.save(rule);
  }

  /** Adds a per-metric-unit earning rule (commission; the rate is non-PII config). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public EarningRule addPerMetricEarning(
      UUID compensationPackageId,
      UUID payComponentId,
      String metricKey,
      Money rate,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    String tenant = TenantContext.require().companyId();
    EarningRule rule =
        EarningRule.perMetricUnit(
            compensationPackageId, payComponentId, metricKey, rate, effectiveFrom, effectiveTo);
    rule.setCompanyId(tenant);
    return earningRuleRepository.save(rule);
  }
}
