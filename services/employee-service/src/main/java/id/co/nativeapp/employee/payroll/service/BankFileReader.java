package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.domain.PayrollRunNotFoundException;
import id.co.nativeapp.employee.payroll.domain.PayrollRunNotPostedException;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.domain.RunStatus;
import id.co.nativeapp.employee.payroll.repository.PayrollRunRepository;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the net-pay bank file for a POSTED payroll run (Track P phase P5) — a CSV a payroll admin
 * hands to their bank for disbursement. The PII boundary is deliberately narrow: {@link
 * Employee#bankAccountForBankFile()} is decrypted ONLY here, and the decrypted value goes straight
 * into the CSV response body — it is NEVER logged. The one audit line this reader emits carries
 * {@code runId} + a row count only (rule 6).
 *
 * <p><strong>Net derivation mirrors {@code MeReader#payslipDetail} exactly</strong> (the single
 * other authoritative net-pay computation in this service): {@code net = Σ(EMPLOYEE-borne EARNING
 * lines) - Σ(EMPLOYEE-borne DEDUCTION lines)}. EMPLOYER-borne lines (e.g. the employer leg of a
 * BPJS contribution) never touch net pay — they are employer cost, not owed to the employee (see
 * {@link PayComponentBearer}'s javadoc).
 *
 * <p><strong>Nothing is silently dropped.</strong> An employee whose decrypted bank account happens
 * to be blank (defensive — the aggregate's constructor requires a non-blank value today, but a
 * future relaxation must not silently vanish a row) still gets a CSV row, with an empty {@code
 * bank_account} cell; the trailing comment row's count always equals the number of data rows, so a
 * recipient can verify none were dropped.
 */
@Service
public class BankFileReader {

  private static final Logger log = LoggerFactory.getLogger(BankFileReader.class);

  private static final String CSV_HEADER =
      "employee_name,bank_account,net_amount_minor,currency,reference";

  private final PayrollRunRepository payrollRunRepository;
  private final PayslipLineRepository payslipLineRepository;
  private final EmployeeRepository employeeRepository;

  public BankFileReader(
      PayrollRunRepository payrollRunRepository,
      PayslipLineRepository payslipLineRepository,
      EmployeeRepository employeeRepository) {
    this.payrollRunRepository = payrollRunRepository;
    this.payslipLineRepository = payslipLineRepository;
    this.employeeRepository = employeeRepository;
  }

  /**
   * Generates the bank file for {@code runId}.
   *
   * @throws PayrollRunNotFoundException if the run is not in the bound tenant (RLS-scoped) -> 404
   * @throws PayrollRunNotPostedException if the run has not reached POSTED -> 409
   */
  @Transactional(readOnly = true)
  public BankFileResult generate(UUID runId) {
    PayrollRun run =
        payrollRunRepository
            .findById(runId)
            .orElseThrow(() -> new PayrollRunNotFoundException(runId));
    if (run.getStatus() != RunStatus.POSTED) {
      throw new PayrollRunNotPostedException(runId, run.getStatus().name());
    }

    // Deliberately a full-entity read (CODE-STRUCTURE §3.3's decrypt-only-via-the-managed-entity
    // exception, mirroring PayslipLineRepository#findActiveLinesForEmployeeYear): amount_enc is
    // PII-ciphertext, decrypted only through PayslipLine's @Convert.
    List<PayslipLine> lines = payslipLineRepository.findByPayrollRunId(runId);

    // Insertion-ordered so the per-employee net accumulates deterministically before the final
    // name-sort below.
    Map<UUID, Long> netMinorByEmployee = new LinkedHashMap<>();
    for (PayslipLine line : lines) {
      if (line.getBearer() != PayComponentBearer.EMPLOYEE) {
        continue; // employer-borne lines are employer cost, never net pay.
      }
      long amountMinor = line.getAmount().amountMinor();
      if (line.getKind() == PayComponentKind.EARNING) {
        netMinorByEmployee.merge(line.getEmployeeId(), amountMinor, Long::sum);
      } else if (line.getKind() == PayComponentKind.DEDUCTION) {
        netMinorByEmployee.merge(line.getEmployeeId(), -amountMinor, Long::sum);
      }
    }

    Map<UUID, Employee> employeesById = new LinkedHashMap<>();
    for (Employee employee : employeeRepository.findAllById(netMinorByEmployee.keySet())) {
      employeesById.put(employee.getId(), employee);
    }

    String reference = "PAYROLL-" + run.getPeriod() + "-" + run.getRunSeq();
    List<BankFileRow> rows = new ArrayList<>(netMinorByEmployee.size());
    for (Map.Entry<UUID, Long> entry : netMinorByEmployee.entrySet()) {
      Employee employee = employeesById.get(entry.getKey());
      if (employee == null) {
        // Should not occur (payslip_line.employee_id references the same tenant's employee row);
        // defensive — the row is still included so the recipient sees a payable amount, never
        // silently drops it.
        log.warn("bank file runId={}: no employee row for a payslip line's employee id", runId);
        rows.add(new BankFileRow(entry.getKey().toString(), "", entry.getValue()));
        continue;
      }
      String bankAccount = employee.bankAccountForBankFile();
      rows.add(
          new BankFileRow(
              employee.getFullName(),
              bankAccount == null ? "" : bankAccount.strip(),
              entry.getValue()));
    }
    rows.sort((a, b) -> a.employeeName().compareToIgnoreCase(b.employeeName()));

    StringBuilder csv = new StringBuilder();
    csv.append(CSV_HEADER).append('\n');
    for (BankFileRow row : rows) {
      csv.append(csvField(row.employeeName()))
          .append(',')
          .append(csvField(row.bankAccount()))
          .append(',')
          .append(row.netAmountMinor())
          .append(',')
          .append(run.getBaseCurrency())
          .append(',')
          .append(csvField(reference))
          .append('\n');
    }
    csv.append("# row_count=").append(rows.size()).append('\n');

    // Audit log: runId + row count ONLY — zero PII (rule 6). No employee name, no bank account, no
    // amount.
    log.info("bank file generated runId={} rows={}", runId, rows.size());

    return new BankFileResult(csv.toString(), run.getPeriod(), run.getRunSeq());
  }

  /** RFC 4180-ish CSV field escaping: quote a field containing a comma/quote/newline. */
  private static String csvField(String value) {
    if (value == null) {
      return "";
    }
    if (value.contains(",")
        || value.contains("\"")
        || value.contains("\n")
        || value.contains("\r")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  private record BankFileRow(String employeeName, String bankAccount, long netAmountMinor) {}
}
