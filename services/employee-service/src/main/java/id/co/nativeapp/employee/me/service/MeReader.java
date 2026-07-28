package id.co.nativeapp.employee.me.service;

import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.dto.ContractResponse;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.employee.repository.EmploymentContractRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.me.dto.MeProfileResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipDetailResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipHeaderResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipLineResponse;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.repository.PayrollRunRepository;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The employee self-service read side ({@code /api/v1/me/**}). The caller is resolved EXCLUSIVELY
 * from {@link TenantContext#require()}'s actor — the JWT {@code sub} the gateway injects — via the
 * V7 employee↔login link; no request parameter can widen the view, so a caller only ever reads
 * their OWN rows (within the RLS-scoped tenant, rule 5).
 *
 * <p><strong>The PII stance (rule 6):</strong> NIK/bank stay MASKED even to the person themselves;
 * only payslip AMOUNTS are decrypted, and only via {@link #payslipDetail(UUID)} which loads
 * strictly the caller's own lines. An unlinked login fails closed with {@link
 * EmployeeNotLinkedException} (404) on every read.
 */
@Service
public class MeReader {

  private final EmployeeRepository employeeRepository;
  private final AssignmentRepository assignmentRepository;
  private final EmploymentContractRepository contractRepository;
  private final PayslipLineRepository payslipLineRepository;
  private final PayrollRunRepository payrollRunRepository;

  public MeReader(
      EmployeeRepository employeeRepository,
      AssignmentRepository assignmentRepository,
      EmploymentContractRepository contractRepository,
      PayslipLineRepository payslipLineRepository,
      PayrollRunRepository payrollRunRepository) {
    this.employeeRepository = employeeRepository;
    this.assignmentRepository = assignmentRepository;
    this.contractRepository = contractRepository;
    this.payslipLineRepository = payslipLineRepository;
    this.payrollRunRepository = payrollRunRepository;
  }

  /** The caller's profile (PII masked) with their assignments and contracts. */
  @Transactional(readOnly = true)
  public MeProfileResponse profile() {
    Employee me = resolveMe();
    List<AssignmentResponse> assignments =
        assignmentRepository.findByEmployeeId(me.getId()).stream()
            .map(AssignmentResponse::from)
            .toList();
    List<ContractResponse> contracts =
        contractRepository.findByEmployeeId(me.getId()).stream()
            .map(ContractResponse::from)
            .toList();
    return new MeProfileResponse(
        me.getId(),
        me.getFullName(),
        me.getPtkpStatus().name(),
        me.getStatus().name(),
        me.maskedNik(),
        me.maskedBankAccount(),
        assignments,
        contracts);
  }

  /** The caller's payslip run headers, newest first (no amounts — the detail read decrypts). */
  @Transactional(readOnly = true)
  public List<MyPayslipHeaderResponse> payslipHeaders(String period) {
    Employee me = resolveMe();
    String normalized = (period == null || period.isBlank()) ? null : period.strip();
    // Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
    return payslipLineRepository.findMyPayslipHeaders(me.getId(), normalized).stream()
        .map(
            v ->
                new MyPayslipHeaderResponse(
                    v.getRunId(),
                    v.getPeriod(),
                    v.getRunSeq(),
                    v.getPostedAt(),
                    v.getLineCount(),
                    Boolean.TRUE.equals(v.getIllustrative())))
        .toList();
  }

  /**
   * The caller's OWN payslip for one run, with REAL amounts — the only consumer of the decrypted
   * line read. Empty when the run is unknown to the tenant OR carries none of the caller's lines
   * (both collapse to the same 404 upstream — anti-enumeration).
   */
  @Transactional(readOnly = true)
  public Optional<MyPayslipDetailResponse> payslipDetail(UUID runId) {
    Employee me = resolveMe();
    List<PayslipLine> lines =
        payslipLineRepository.findByPayrollRunIdAndEmployeeId(runId, me.getId());
    if (lines.isEmpty()) {
      return Optional.empty();
    }
    PayrollRun run = payrollRunRepository.findById(runId).orElse(null);
    if (run == null) {
      return Optional.empty();
    }

    long gross = 0;
    long deductions = 0;
    boolean illustrative = false;
    String currency = run.getBaseCurrency();
    List<MyPayslipLineResponse> lineResponses = new java.util.ArrayList<>();
    for (PayslipLine line : lines) {
      long amountMinor = line.getAmount().amountMinor();
      String lineCurrency = line.getAmount().currency().getCurrencyCode();
      boolean employeeBorne = line.getBearer() == PayComponentBearer.EMPLOYEE;
      if (employeeBorne && line.getKind() == PayComponentKind.EARNING) {
        gross += amountMinor;
      } else if (employeeBorne && line.getKind() == PayComponentKind.DEDUCTION) {
        deductions += amountMinor;
      }
      illustrative = illustrative || line.isIllustrative();
      lineResponses.add(
          new MyPayslipLineResponse(
              line.getComponentKey(),
              line.getKind().name(),
              line.getBearer().name(),
              amountMinor,
              lineCurrency,
              line.isIllustrative()));
    }
    return Optional.of(
        new MyPayslipDetailResponse(
            runId,
            run.getPeriod(),
            run.getRunSeq(),
            currency,
            gross,
            deductions,
            gross - deductions,
            illustrative,
            lineResponses));
  }

  /**
   * Resolves the calling login to their employee row: actor (the JWT sub) → {@code
   * employee.user_id}. RLS scopes the lookup to the bound tenant, so the same sub in another tenant
   * resolves nothing (fail-closed 404).
   */
  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
