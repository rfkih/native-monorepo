package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.MissingEmploymentContractForRunException;
import id.co.nativeapp.employee.payroll.domain.UnsupportedEmploymentTypeForRunException;
import id.co.nativeapp.employee.payroll.dto.PayslipLineResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0055 §5 (the P0 employment-type scope gate, INCLUDING its domain-specialist-review fixes:
 * INTERN gated, fail-closed on a missing/lapsed contract) and §2 (the P1 "kontrak == tetap for
 * monthly pay" pin), over the same real RLS-enforcing PostgreSQL harness as {@link
 * PayrollRunEndToEndTest}.
 */
@SpringBootTest
class PayrollEmploymentTypeScopeGateTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final long BASE_MINOR = 20_000_000L;

  @Autowired private IllustrativeStatutorySeedWriter seeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunReader payrollRunReader;

  /**
   * (a) A run that includes a {@code DAILY_CASUAL} employee is rejected 422 (via {@link
   * UnsupportedEmploymentTypeForRunException}) and produces NO payslip lines — the run's whole
   * {@code calculate} transaction rolls back, so even a fully-set-up DAILY_CASUAL employee (comp
   * package + assignment, exactly like a supported employee) never reaches the calculator.
   */
  @Test
  void aRunIncludingADailyCasualEmployeeIsRejected422AndPersistsNoPayslipLines() {
    UUID[] employeeIdHolder = new UUID[1];

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> {
                      UUID outlet = seedOutletAndCatalog();
                      UUID employeeId =
                          makeEmployeeWithContract(
                              "Casual Worker",
                              "3209111111111111",
                              "1111000022223333",
                              "DAILY_CASUAL",
                              outlet);
                      employeeIdHolder[0] = employeeId;
                      return payrollRunService.calculate(
                          new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);
                    }))
        .isInstanceOf(UnsupportedEmploymentTypeForRunException.class)
        .hasMessageContaining("DAILY_CASUAL")
        .hasMessageContaining(employeeIdHolder[0].toString());

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payslip_line WHERE employee_id = ?",
                employeeIdHolder[0]))
        .as("no payslip line was ever persisted for the rejected employee")
        .isZero();
    assertThat(countAsTenant(TENANT_A, "SELECT count(*) FROM payroll_run WHERE status = 'POSTED'"))
        .as("no run reached POSTED")
        .isZero();
  }

  /**
   * (b) A {@code CONTRACT} (kontrak) employee computes BYTE-IDENTICALLY to an otherwise-identical
   * {@code PERMANENT} employee run in the SAME payroll run — same component set, kind, bearer, GL
   * account, decrypted amount/currency, and stamped {@code rule_version} (ADR 0055 §2: PKWT is
   * pegawai tetap for monthly pay, not a simplification).
   */
  @Test
  void aContractEmployeeComputesByteIdenticallyToAnOtherwiseIdenticalPermanentEmployee()
      throws Exception {
    UUID[] permanentId = new UUID[1];
    UUID[] contractId = new UUID[1];
    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              UUID outlet = seedOutletAndCatalog();
              permanentId[0] =
                  makeEmployeeWithContract(
                      "Tetap", "3209222222222222", "2222333344445555", "PERMANENT", outlet);
              contractId[0] =
                  makeEmployeeWithContract(
                      "Kontrak", "3209333333333333", "3333444455556666", "CONTRACT", outlet);
              return payrollRunService
                  .calculateAndPost(
                      new RunPayrollCommand(
                          "2026-06", List.of(permanentId[0], contractId[0]), List.of()),
                      IDR)
                  .getId();
            });

    List<PayslipLineResponse> permanentLines =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> payrollRunReader.findPayslipAuthorized(runId, permanentId[0]));
    List<PayslipLineResponse> contractLines =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> payrollRunReader.findPayslipAuthorized(runId, contractId[0]));

    assertThat(permanentLines).isNotEmpty();
    assertThat(contractLines)
        .as("CONTRACT computes byte-identically to PERMANENT for monthly pay (ADR 0055 §2)")
        .containsExactlyInAnyOrderElementsOf(permanentLines);
  }

  /**
   * (c1) {@code INTERN} is now GATED, not allowed (domain-specialist review, P0 fix): a magang
   * stipend is usually <em>bukan pegawai</em>, so computing an intern as pegawai tetap would be a
   * misclassification. Rejected 422, zero payslip lines — same shape as the DAILY_CASUAL case.
   */
  @Test
  void anInternEmployeeIsRejected422AndPersistsNoPayslipLines() {
    UUID[] employeeIdHolder = new UUID[1];

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> {
                      UUID outlet = seedOutletAndCatalog();
                      UUID employeeId =
                          makeEmployeeWithContract(
                              "Intern", "3209444444444444", "4444555566667777", "INTERN", outlet);
                      employeeIdHolder[0] = employeeId;
                      return payrollRunService.calculate(
                          new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);
                    }))
        .isInstanceOf(UnsupportedEmploymentTypeForRunException.class)
        .hasMessageContaining("INTERN")
        .hasMessageContaining(employeeIdHolder[0].toString());

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payslip_line WHERE employee_id = ?",
                employeeIdHolder[0]))
        .as("no payslip line was ever persisted for the rejected intern")
        .isZero();
  }

  /**
   * (c2) {@code PROBATION} (masa percobaan) stays ALLOWED — a full pegawai tetap, same monthly
   * gross-to-net path as PERMANENT, still computes successfully post-gate.
   */
  @Test
  void aProbationEmployeeStillComputesSuccessfully() throws Exception {
    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              UUID outlet = seedOutletAndCatalog();
              UUID probationId =
                  makeEmployeeWithContract(
                      "Probation", "3209555555555555", "5555666677778888", "PROBATION", outlet);
              return payrollRunService
                  .calculateAndPost(
                      new RunPayrollCommand("2026-06", List.of(probationId), List.of()), IDR)
                  .getId();
            });

    assertThat(
            countAsTenant(
                TENANT_A, "SELECT count(*) FROM payslip_line WHERE payroll_run_id = ?", runId))
        .isGreaterThan(0L);
  }

  /**
   * (d) MIXED RUN (code-review W2): a run containing BOTH a {@code PERMANENT} and a {@code
   * DAILY_CASUAL} employee is rejected 422 with ZERO payslip lines and ZERO POSTED runs for BOTH
   * employees — pins that the WHOLE run is rejected (not just the unsupported person's slice) and
   * that every employee in scope is scanned, not merely the first one iterated.
   */
  @Test
  void aMixedRunWithAPermanentAndADailyCasualEmployeeIsRejectedForBoth() {
    UUID[] permanentIdHolder = new UUID[1];
    UUID[] casualIdHolder = new UUID[1];

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> {
                      UUID outlet = seedOutletAndCatalog();
                      UUID permanentId =
                          makeEmployeeWithContract(
                              "Tetap", "3209666666666666", "6666777788889999", "PERMANENT", outlet);
                      UUID casualId =
                          makeEmployeeWithContract(
                              "Casual",
                              "3209777777777777",
                              "7777888899990000",
                              "DAILY_CASUAL",
                              outlet);
                      permanentIdHolder[0] = permanentId;
                      casualIdHolder[0] = casualId;
                      return payrollRunService.calculate(
                          new RunPayrollCommand(
                              "2026-06", List.of(permanentId, casualId), List.of()),
                          IDR);
                    }))
        .isInstanceOf(UnsupportedEmploymentTypeForRunException.class)
        .hasMessageContaining("DAILY_CASUAL");

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payslip_line WHERE employee_id = ?",
                permanentIdHolder[0]))
        .as("the PERMANENT employee's slice was never computed either — the WHOLE run rejected")
        .isZero();
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payslip_line WHERE employee_id = ?",
                casualIdHolder[0]))
        .as("no payslip line for the DAILY_CASUAL employee")
        .isZero();
    assertThat(countAsTenant(TENANT_A, "SELECT count(*) FROM payroll_run WHERE status = 'POSTED'"))
        .as("no run reached POSTED")
        .isZero();
  }

  /**
   * (e) NO-CONTRACT / lapsed contract (domain-specialist review, P0 fail-closed fix): an employee
   * with an OPEN compensation package but whose employment_contract's {@code effective_to} is
   * BEFORE the run period is rejected 422 via {@link MissingEmploymentContractForRunException} —
   * the gate does NOT silently default an unclassifiable employee to pegawai tetap.
   */
  @Test
  void anEmployeeWithALapsedContractIsRejected422AndPersistsNoPayslipLines() {
    UUID[] employeeIdHolder = new UUID[1];

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> {
                      UUID outlet = seedOutletAndCatalog();
                      UUID employeeId =
                          makeEmployeeWithLapsedContract(
                              "Lapsed", "3209888888888888", "8888999900001111", outlet);
                      employeeIdHolder[0] = employeeId;
                      return payrollRunService.calculate(
                          new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);
                    }))
        .isInstanceOf(MissingEmploymentContractForRunException.class)
        .hasMessageContaining(employeeIdHolder[0].toString());

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payslip_line WHERE employee_id = ?",
                employeeIdHolder[0]))
        .as("no payslip line was ever persisted for the lapsed-contract employee")
        .isZero();
  }

  private UUID seedOutletAndCatalog() {
    seeder.seed(IDR);
    UUID outlet = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
    return outlet;
  }

  private UUID makeEmployeeWithContract(
      String name, String nik, String bankAccount, String employmentType, UUID outlet) {
    UUID employeeId =
        employeeService.create(new CreateEmployeeCommand(name, "TK0", nik, bankAccount)).getId();
    var contract =
        employeeService.addContract(
            new AddContractCommand(
                employeeId,
                employmentType,
                LEGAL_EMPLOYER,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(9999, 12, 31)));
    compensationWriter.createPackage(
        employeeId,
        contract.getId(),
        Money.ofMinor(BASE_MINOR, IDR),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(9999, 12, 31));
    assignmentService.add(
        new AddAssignmentCommand(
            employeeId,
            outlet,
            null,
            "cashier",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(9999, 12, 31)));
    return employeeId;
  }

  /**
   * An employee whose {@code employment_contract} LAPSED before the "2026-06" run period (effective
   * 2025-01-01 to 2025-12-31) but whose compensation package is still OPEN — the fail-closed gate's
   * target scenario: a real HR data gap (someone forgot to renew/extend the contract), not a
   * fabricated edge case.
   */
  private UUID makeEmployeeWithLapsedContract(
      String name, String nik, String bankAccount, UUID outlet) {
    UUID employeeId =
        employeeService.create(new CreateEmployeeCommand(name, "TK0", nik, bankAccount)).getId();
    var contract =
        employeeService.addContract(
            new AddContractCommand(
                employeeId,
                "PERMANENT",
                LEGAL_EMPLOYER,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31)));
    compensationWriter.createPackage(
        employeeId,
        contract.getId(),
        Money.ofMinor(BASE_MINOR, IDR),
        LocalDate.of(2025, 1, 1),
        LocalDate.of(9999, 12, 31));
    assignmentService.add(
        new AddAssignmentCommand(
            employeeId,
            outlet,
            null,
            "cashier",
            LocalDate.of(2025, 1, 1),
            LocalDate.of(9999, 12, 31)));
    return employeeId;
  }
}
