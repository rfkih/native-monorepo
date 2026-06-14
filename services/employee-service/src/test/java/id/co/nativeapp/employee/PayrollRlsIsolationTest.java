package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.assignment.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.AssignmentService;
import id.co.nativeapp.employee.employee.AddContractCommand;
import id.co.nativeapp.employee.employee.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.EmployeeService;
import id.co.nativeapp.employee.org.OrgProjectionService;
import id.co.nativeapp.employee.org.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.payroll.CompensationWriter;
import id.co.nativeapp.employee.payroll.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.PayrollRunReader;
import id.co.nativeapp.employee.payroll.PayrollRunService;
import id.co.nativeapp.employee.payroll.RunPayrollCommand;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-tenant isolation of a payroll run + its payslips via the auto-applied RLS (rule 5). Tenant
 * A runs payroll; tenant B, in its own scope, cannot read A's run nor A's payslip lines. No manual
 * {@code WHERE company_id} anywhere — purely RLS (Postgres Testcontainers, non-superuser app role).
 */
@SpringBootTest
class PayrollRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR_A = "hr-a@example.co.id";
  private static final String ACTOR_B = "hr-b@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";

  @Autowired private IllustrativeStatutorySeedWriter seeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private PayrollRunReader payrollRunReader;

  @Test
  void tenantBCannotReadTenantAsPayrollRunOrPayslips() throws Exception {
    UUID[] ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              seeder.seed(IDR);
              UUID outlet = UUID.randomUUID();
              orgProjectionService.apply(
                  new OrgUnitProjectedEvent(
                      UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Budi", "TK0", "3201234567890123", "1234567890123456"))
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
                  Money.ofMinor(20_000_000L, IDR),
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
              var run =
                  payrollRunService.calculateAndPost(
                      new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);
              return new UUID[] {run.getId(), employeeId};
            });
    UUID runId = ids[0];
    UUID employeeId = ids[1];

    // A sees its own run + payslip lines.
    Optional<?> seenByA =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId));
    assertThat(seenByA).isPresent();
    assertThat(
            TenantContext.callAs(
                TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipMasked(runId, employeeId)))
        .isNotEmpty();

    // B, in its own scope, sees NEITHER (RLS fail-closed).
    Optional<?> seenByB =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> payrollRunReader.findRun(runId));
    assertThat(seenByB).isEmpty();
    assertThat(
            TenantContext.callAs(
                TENANT_B, ACTOR_B, () -> payrollRunReader.findPayslipMasked(runId, employeeId)))
        .isEmpty();
  }
}
