package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeReader;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (d) — cross-tenant isolation of employee + assignment via the auto-applied RLS.
 *
 * <p>Tenant A creates an employee and an assignment. Tenant B, in its own bound scope, cannot see
 * A's employee (the {@code GET} read returns empty — fail closed) and cannot create an assignment
 * for A's employee against A's org unit (A's org unit is invisible in B's read model, so the
 * legal-employer resolution fails). No manual {@code WHERE company_id} anywhere — the isolation is
 * purely RLS (rule 5).
 */
@SpringBootTest
class CrossTenantIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final String ACTOR_B = "hr-admin-b@example.co.id";
  private static final UUID LEGAL_EMPLOYER_A =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  @Autowired private EmployeeService employeeService;
  @Autowired private EmployeeReader employeeReader;
  @Autowired private AssignmentService assignmentService;
  @Autowired private id.co.nativeapp.employee.org.service.OrgProjectionService orgProjectionService;

  @Test
  void tenantBCannotReadTenantAsEmployee() throws Exception {
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                employeeService
                    .create(
                        new CreateEmployeeCommand(
                            "Budi Santoso", "K0", "3201111111111111", "1111222233334444"))
                    .getId());

    // A sees its own employee.
    Optional<?> seenByA =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> employeeReader.findWithAssignments(employeeId));
    assertThat(seenByA).isPresent();

    // B, in its own tenant scope, does NOT see A's employee (RLS fail-closed).
    Optional<?> seenByB =
        TenantContext.callAs(
            TENANT_B, ACTOR_B, () -> employeeReader.findWithAssignments(employeeId));
    assertThat(seenByB).isEmpty();
  }

  @Test
  void tenantBCannotResolveTenantAsOrgUnitForAnAssignment() throws Exception {
    // A seeds an org unit projection (via the consumer service, so the auto-RLS aspect engages) and
    // creates an employee.
    UUID orgUnitA = UUID.randomUUID();
    UUID employeeB;
    orgProjectionService.apply(
        new id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitA, TENANT_A, LEGAL_EMPLOYER_A, "OUTLET", true));

    employeeB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                employeeService
                    .create(
                        new CreateEmployeeCommand(
                            "Siti", "TK0", "3209999999999999", "5555666677778888"))
                    .getId());

    // B tries to assign ITS employee to A's org unit. A's org unit is invisible to B under RLS, so
    // the legal-employer resolution from the local read model fails -> IllegalArgumentException
    // (400).
    // callAs propagates a RuntimeException from the action unwrapped, so the
    // IllegalArgumentException
    // surfaces directly.
    final UUID employeeBId = employeeB;
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR_B,
                    () ->
                        assignmentService.add(
                            new AddAssignmentCommand(
                                employeeBId,
                                orgUnitA,
                                null,
                                "cashier",
                                LocalDate.of(2026, 6, 1),
                                LocalDate.of(2026, 12, 31)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown org unit");
  }
}
