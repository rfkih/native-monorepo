package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.employee.repository.EmploymentContractRepository;
import id.co.nativeapp.employee.payroll.domain.CompensationNotFoundException;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.domain.EarningRule;
import id.co.nativeapp.employee.payroll.domain.OverlappingCompensationException;
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
  private final EmployeeRepository employeeRepository;
  private final EmploymentContractRepository contractRepository;

  public CompensationWriter(
      CompensationPackageRepository compPackageRepository,
      EarningRuleRepository earningRuleRepository,
      EmployeeRepository employeeRepository,
      EmploymentContractRepository contractRepository) {
    this.compPackageRepository = compPackageRepository;
    this.earningRuleRepository = earningRuleRepository;
    this.employeeRepository = employeeRepository;
    this.contractRepository = contractRepository;
  }

  /**
   * The console create path: validates the target BEFORE persisting — the employee must be visible
   * in the bound tenant (→ 404), the contract must belong to that employee (→ 400), and the new
   * period must not overlap ANY existing package of the employee (→ 409; the payroll run SUMS every
   * covering package, so an overlap silently double-pays). Then persists via the same
   * PII-encrypting entity as {@link #createPackage}.
   *
   * @throws EmployeeNotFoundException if the employee is not visible in the bound tenant
   * @throws IllegalArgumentException if the contract does not belong to the employee
   * @throws OverlappingCompensationException if an existing package overlaps the new period
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CompensationPackage createValidated(
      UUID employeeId,
      UUID employmentContractId,
      Money basePay,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    String tenant = TenantContext.require().companyId();
    if (employeeRepository.findById(employeeId).isEmpty()) {
      throw new EmployeeNotFoundException(employeeId);
    }
    boolean contractBelongs =
        contractRepository.findByEmployeeId(employeeId).stream()
            .anyMatch(c -> c.getId().equals(employmentContractId));
    if (!contractBelongs) {
      throw new IllegalArgumentException(
          "Contract " + employmentContractId + " does not belong to employee " + employeeId);
    }
    CompensationPackage candidate =
        new CompensationPackage(
            employeeId,
            employmentContractId,
            basePay,
            PayFrequency.MONTHLY,
            effectiveFrom,
            effectiveTo);
    for (CompensationPackage existing : compPackageRepository.findByEmployeeId(employeeId)) {
      if (existing.overlaps(candidate.getEffectiveFrom(), candidate.getEffectiveTo())) {
        throw new OverlappingCompensationException(employeeId, existing.getId());
      }
    }
    candidate.setCompanyId(tenant);
    CompensationPackage saved = compPackageRepository.save(candidate);
    compPackageRepository.flush();
    return saved;
  }

  /**
   * Ends an OPEN package on the given date (effective-dated close — the "change salary" flow is
   * end-old + create-new). The package must be visible under RLS AND belong to the path's employee
   * — anything else collapses to the same 404 (anti-enumeration).
   *
   * @throws CompensationNotFoundException if no such package is visible for that employee (→ 404)
   * @throws id.co.nativeapp.employee.payroll.domain.CompensationAlreadyEndedException if it is not
   *     open (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CompensationPackage endPackage(UUID employeeId, UUID packageId, LocalDate endOn) {
    TenantContext.require();
    CompensationPackage pkg =
        compPackageRepository
            .findById(packageId)
            .filter(p -> p.getEmployeeId().equals(employeeId))
            .orElseThrow(() -> new CompensationNotFoundException(packageId));
    pkg.end(endOn);
    CompensationPackage saved = compPackageRepository.save(pkg);
    compPackageRepository.flush();
    return saved;
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
