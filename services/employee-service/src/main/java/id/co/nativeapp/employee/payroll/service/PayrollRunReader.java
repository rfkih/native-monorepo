package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.dto.AllocationSummaryResponse;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.PayslipIndexRowResponse;
import id.co.nativeapp.employee.payroll.dto.PayslipLineResponse;
import id.co.nativeapp.employee.payroll.repository.LaborCostAllocationRepository;
import id.co.nativeapp.employee.payroll.repository.PayrollRunRepository;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side service (the {@code *Reader} stereotype) for payroll runs and payslips. Reads are
 * RLS-scoped (rule 5). Payslip line amounts are PII and are MASKED by default; only an authorized
 * caller path receives decrypted amounts.
 */
@Service
public class PayrollRunReader {

  private static final Pattern PERIOD_PATTERN = Pattern.compile("\\d{4}-\\d{2}");

  private final PayrollRunRepository runRepository;
  private final PayslipLineRepository payslipLineRepository;
  private final LaborCostAllocationRepository allocationRepository;

  public PayrollRunReader(
      PayrollRunRepository runRepository,
      PayslipLineRepository payslipLineRepository,
      LaborCostAllocationRepository allocationRepository) {
    this.runRepository = runRepository;
    this.payslipLineRepository = payslipLineRepository;
    this.allocationRepository = allocationRepository;
  }

  /**
   * The period's runs, newest {@code run_seq} first — company totals only (a projection read; a
   * re-run appears as an additional posting).
   *
   * @throws IllegalArgumentException on a missing/malformed period (→ 400)
   */
  @Transactional(readOnly = true)
  public List<PayrollRunResponse> listByPeriod(String period) {
    if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
      throw new IllegalArgumentException("period must be YYYY-MM");
    }
    // Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
    return runRepository.findRunViewsByPeriod(period).stream()
        .map(
            v ->
                new PayrollRunResponse(
                    v.getId(),
                    v.getPeriod(),
                    v.getRunSeq(),
                    v.getStatus(),
                    v.getBaseCurrency(),
                    v.getGrossTotalMinor(),
                    v.getEmployeeDeductionTotalMinor(),
                    v.getEmployerContributionTotalMinor(),
                    v.getNetTotalMinor(),
                    Boolean.TRUE.equals(v.getUsesIllustrativeRules()),
                    v.getPostedAt()))
        .toList();
  }

  /**
   * A run's labor-cost buckets, SUMmed per (outlet, GL account) across employees — the aggregation
   * is the privacy guard (rule 6). Empty if the run is not visible to the bound tenant.
   */
  @Transactional(readOnly = true)
  public Optional<List<AllocationSummaryResponse>> allocationSummaries(UUID runId) {
    if (!runRepository.existsById(runId)) {
      return Optional.empty();
    }
    return Optional.of(
        allocationRepository.findAllocationSummaries(runId).stream()
            .map(
                v ->
                    new AllocationSummaryResponse(
                        v.getOutletOrgUnitId(),
                        v.getGlAccount(),
                        v.getAmountMinor(),
                        v.getCurrency(),
                        Boolean.TRUE.equals(v.getUnallocated())))
            .toList());
  }

  /** A run's payslip index — one row per employee, counts only, no amounts (rule 6). */
  @Transactional(readOnly = true)
  public Optional<List<PayslipIndexRowResponse>> payslipIndex(UUID runId) {
    if (!runRepository.existsById(runId)) {
      return Optional.empty();
    }
    return Optional.of(
        payslipLineRepository.findPayslipIndex(runId).stream()
            .map(
                v ->
                    new PayslipIndexRowResponse(
                        v.getEmployeeId(),
                        v.getFullName(),
                        v.getLineCount(),
                        Boolean.TRUE.equals(v.getIllustrative())))
            .toList());
  }

  /** A run's company-level summary (no PII), or empty if not visible to the bound tenant. */
  @Transactional(readOnly = true)
  public Optional<PayrollRunResponse> findRun(UUID runId) {
    return runRepository.findById(runId).map(PayrollRunResponse::from);
  }

  /** An employee's payslip lines for a run, MASKED (no plaintext salary crosses the boundary). */
  @Transactional(readOnly = true)
  public List<PayslipLineResponse> findPayslipMasked(UUID runId, UUID employeeId) {
    return payslipLineRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId).stream()
        .map(PayslipLineResponse::masked)
        .toList();
  }

  /**
   * An employee's payslip lines for a run, with DECRYPTED amounts. Restricted to an authorized
   * caller (the employee's own worker surface or an authorized HR role); the controller must gate
   * access before calling this.
   */
  @Transactional(readOnly = true)
  public List<PayslipLineResponse> findPayslipAuthorized(UUID runId, UUID employeeId) {
    return payslipLineRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId).stream()
        .map(PayslipLineResponse::authorized)
        .toList();
  }
}
