package id.co.nativeapp.employee.payroll;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side service (the {@code *Reader} stereotype) for payroll runs and payslips. Reads are
 * RLS-scoped (rule 5). Payslip line amounts are PII and are MASKED by default; only an authorized
 * caller path receives decrypted amounts.
 */
@Service
public class PayrollRunReader {

  private final PayrollRunRepository runRepository;
  private final PayslipLineRepository payslipLineRepository;

  public PayrollRunReader(
      PayrollRunRepository runRepository, PayslipLineRepository payslipLineRepository) {
    this.runRepository = runRepository;
    this.payslipLineRepository = payslipLineRepository;
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
