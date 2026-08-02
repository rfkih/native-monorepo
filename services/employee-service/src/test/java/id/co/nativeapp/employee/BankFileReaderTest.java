package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.config.PiiCipher;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.PayrollRunNotFoundException;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.service.BankFileReader;
import id.co.nativeapp.employee.payroll.service.BankFileResult;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link BankFileReader} over a real RLS-enforcing PostgreSQL (Track P phase P5): the net-pay
 * figure mirrors the run's own authoritative net total (the SAME formula {@code MeReader
 * #payslipDetail} documents — {@code Σ EMPLOYEE-borne EARNING − Σ EMPLOYEE-borne DEDUCTION}), the
 * bank account is decrypted ONLY at this boundary and appears in the CSV body but NEVER in a log
 * line, a blank bank account still produces a row (never silently dropped), and an unknown run is
 * rejected.
 */
@SpringBootTest
class BankFileReaderTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "bankfile@integration.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final long BASE_MINOR = 20_000_000L;
  private static final long EXPECTED_NET_MINOR = 17_798_333L;
  private static final String BANK_ACCOUNT = "1234567890123456";

  @Autowired private IllustrativeStatutorySeedWriter seeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private BankFileReader bankFileReader;
  @Autowired private PiiCipher piiCipher;

  @Test
  void theNetPayMatchesTheRunsOwnTotalAndTheBankAccountIsDecryptedOnlyInTheCsvNeverInALog()
      throws Exception {
    Logger employeeLogger = (Logger) LoggerFactory.getLogger("id.co.nativeapp.employee");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    employeeLogger.addAppender(appender);
    employeeLogger.setLevel(Level.TRACE);

    BankFileResult result;
    try {
      result =
          TenantContext.callAs(TENANT_A, ACTOR_A, () -> bankFileReader.generate(setUpAndRun()));
    } finally {
      employeeLogger.detachAppender(appender);
    }

    String csv = result.csv();
    assertThat(csv).startsWith("employee_name,bank_account,net_amount_minor,currency,reference\n");
    // The single employee's net EXACTLY matches the run's own authoritative net total (single
    // -employee run, PayrollRunEndToEndTest's own figure for the same inputs) — the SAME formula
    // MeReader#payslipDetail documents.
    assertThat(csv)
        .contains("Budi," + BANK_ACCOUNT + "," + EXPECTED_NET_MINOR + ",IDR,PAYROLL-2026-06-1");
    assertThat(csv).contains("# row_count=1");
    assertThat(result.period()).isEqualTo("2026-06");
    assertThat(result.runSeq()).isEqualTo(1);

    // No PII anywhere in the logs (rule 6): neither the bank account nor the net amount.
    for (ILoggingEvent event : appender.list) {
      String line = event.getFormattedMessage();
      assertThat(line).doesNotContain(BANK_ACCOUNT);
      assertThat(line).doesNotContain(Long.toString(EXPECTED_NET_MINOR));
    }
    // The one audit line carries runId + row count only.
    boolean audited =
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getFormattedMessage().contains("bank file generated")
                        && e.getFormattedMessage().contains("rows=1"));
    assertThat(audited).as("the audit line is present").isTrue();
  }

  @Test
  void anEmployeeWithABlankDecryptedBankAccountStillGetsARowWithAnEmptyCellNeverDropped()
      throws Exception {
    UUID runId = TenantContext.callAs(TENANT_A, ACTOR_A, this::setUpAndRun);

    // Simulate a blank decrypted bank account by overwriting the ciphertext column directly with
    // an encrypted empty string (Employee's constructor requires a non-blank value today, so this
    // is otherwise unreachable — a defensive test of BankFileReader's "never silently drop the
    // row" contract for a future relaxation).
    String blankCiphertext = piiCipher.encryptToString("");
    updateBankAccountAsAdmin(blankCiphertext);

    BankFileResult result =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> bankFileReader.generate(runId));
    assertThat(result.csv()).contains("Budi,," + EXPECTED_NET_MINOR + ",IDR,PAYROLL-2026-06-1");
    assertThat(result.csv()).contains("# row_count=1");
  }

  @Test
  void anUnknownRunIsRejected() {
    UUID runId = UUID.randomUUID();
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> bankFileReader.generate(runId)))
        .isInstanceOf(PayrollRunNotFoundException.class);
  }

  // ----------------------------------------------------------------------- helpers

  private UUID setUpAndRun() {
    seeder.seed(IDR);
    UUID outlet = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
    UUID employeeId =
        employeeService
            .create(new CreateEmployeeCommand("Budi", "TK0", "3201234567890123", BANK_ACCOUNT))
            .getId();
    var contract =
        employeeService.addContract(
            new AddContractCommand(
                employeeId,
                "PERMANENT",
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
    return payrollRunService
        .calculateAndPost(new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR)
        .getId();
  }

  private void updateBankAccountAsAdmin(String ciphertext) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("UPDATE employee SET bank_account = ? WHERE company_id = ?")) {
      ps.setString(1, ciphertext);
      ps.setString(2, TENANT_A);
      ps.executeUpdate();
    }
  }
}
