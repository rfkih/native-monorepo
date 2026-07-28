package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.dto.CompensationResponse;
import id.co.nativeapp.employee.payroll.repository.CompensationPackageRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the console compensation surface: masked reads here, writes via {@link
 * CompensationWriter} (the {@code *Writer} pattern owns the tx + RLS GUC + validations). Base pay
 * is salary PII (rule 6): every response this service produces is MASKED — the read path never
 * selects the ciphertext, and the write path maps the persisted entity to a masked DTO without
 * touching the amount.
 */
@Service
public class CompensationService {

  private final CompensationWriter writer;
  private final CompensationPackageRepository packageRepository;
  private final EmployeeRepository employeeRepository;
  private final id.co.nativeapp.employee.payroll.repository.EarningRuleRepository
      commissionRepository;

  public CompensationService(
      CompensationWriter writer,
      CompensationPackageRepository packageRepository,
      EmployeeRepository employeeRepository,
      id.co.nativeapp.employee.payroll.repository.EarningRuleRepository commissionRepository) {
    this.writer = writer;
    this.packageRepository = packageRepository;
    this.employeeRepository = employeeRepository;
    this.commissionRepository = commissionRepository;
  }

  /**
   * The employee's packages, masked — the projection never selects {@code base_pay_enc}.
   *
   * @throws EmployeeNotFoundException if the employee is not visible in the bound tenant (→ 404)
   */
  @Transactional(readOnly = true)
  public List<CompensationResponse> listMasked(UUID employeeId) {
    if (employeeRepository.findById(employeeId).isEmpty()) {
      throw new EmployeeNotFoundException(employeeId);
    }
    // Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
    return packageRepository.findViewsByEmployeeId(employeeId).stream()
        .map(
            v ->
                new CompensationResponse(
                    v.getId(),
                    v.getEmploymentContractId(),
                    v.getPayFrequency(),
                    v.getEffectiveFrom(),
                    v.getEffectiveTo(),
                    CompensationResponse.MASK))
        .toList();
  }

  /** Creates a validated package (see {@link CompensationWriter#createValidated}) — masked echo. */
  public CompensationResponse create(
      UUID employeeId,
      UUID employmentContractId,
      long basePayMinor,
      String currency,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    TenantContext.require();
    CompensationPackage saved =
        writer.createValidated(
            employeeId,
            employmentContractId,
            Money.ofMinor(basePayMinor, currency),
            effectiveFrom,
            effectiveTo);
    return masked(saved);
  }

  /** Ends an OPEN package (see {@link CompensationWriter#endPackage}) — masked echo. */
  public CompensationResponse end(UUID employeeId, UUID packageId, LocalDate endOn) {
    TenantContext.require();
    return masked(writer.endPackage(employeeId, packageId, endOn));
  }

  // ---- commission (non-PII config; real basis points echoed) ----------------------------------

  /** The commission rules on a package (a native projection — no salary ciphertext is read). */
  @Transactional(readOnly = true)
  public List<id.co.nativeapp.employee.payroll.dto.CommissionResponse> listCommissions(
      UUID employeeId, UUID packageId) {
    if (employeeRepository.findById(employeeId).isEmpty()) {
      throw new id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException(employeeId);
    }
    return commissionRepository.findCommissionViews(packageId).stream()
        .map(
            v ->
                new id.co.nativeapp.employee.payroll.dto.CommissionResponse(
                    v.getId(),
                    v.getMetricKey(),
                    v.getPercentBasisPoints() == null ? 0 : v.getPercentBasisPoints(),
                    v.getEffectiveFrom(),
                    v.getEffectiveTo()))
        .toList();
  }

  /** Sets an own-sales commission (see {@link CompensationWriter#addCommission}). */
  public id.co.nativeapp.employee.payroll.dto.CommissionResponse addCommission(
      UUID employeeId, UUID packageId, int percentBasisPoints, String metricKey) {
    TenantContext.require();
    return toCommission(writer.addCommission(employeeId, packageId, percentBasisPoints, metricKey));
  }

  /** Ends an open commission (see {@link CompensationWriter#endCommission}). */
  public id.co.nativeapp.employee.payroll.dto.CommissionResponse endCommission(
      UUID employeeId, UUID packageId, UUID ruleId, LocalDate endOn) {
    TenantContext.require();
    return toCommission(writer.endCommission(employeeId, packageId, ruleId, endOn));
  }

  private static id.co.nativeapp.employee.payroll.dto.CommissionResponse toCommission(
      id.co.nativeapp.employee.payroll.domain.EarningRule rule) {
    return new id.co.nativeapp.employee.payroll.dto.CommissionResponse(
        rule.getId(),
        rule.getMetricKey(),
        rule.getPercentBasisPoints() == null ? 0 : rule.getPercentBasisPoints(),
        rule.getEffectiveFrom(),
        rule.getEffectiveTo());
  }

  private static CompensationResponse masked(CompensationPackage pkg) {
    return new CompensationResponse(
        pkg.getId(),
        pkg.getEmploymentContractId(),
        pkg.getPayFrequency().name(),
        pkg.getEffectiveFrom(),
        pkg.getEffectiveTo(),
        CompensationResponse.MASK);
  }
}
