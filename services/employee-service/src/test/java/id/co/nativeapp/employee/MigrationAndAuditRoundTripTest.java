package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.employee.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.Employee;
import id.co.nativeapp.employee.employee.EmployeeReader;
import id.co.nativeapp.employee.employee.EmployeeService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The mandatory migration + audit round-trip proof (ENGINEERING-STANDARDS §3.2). Booting the full
 * context with {@code ddl-auto=validate} proves Flyway applied the baseline AND Hibernate's mapping
 * validates against the migrated schema (catching drift like a {@code CHAR(3)} vs {@code VARCHAR},
 * or a missing/mismatched column, at boot). An {@link Employee} then round-trips with its {@code
 * company_id} and {@code created_by} populated from the {@link TenantContext} scope by the auditing
 * pipeline.
 */
@SpringBootTest
class MigrationAndAuditRoundTripTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";

  @Autowired private EmployeeService employeeService;
  @Autowired private EmployeeReader employeeReader;

  @Test
  void anEmployeeRoundTripsWithAuditColumnsPopulatedFromTheTenantScope() throws Exception {
    Employee result =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              UUID id =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Budi Santoso", "K1", "3201234567890123", "1234567890123456"))
                      .getId();
              return employeeReader.findEmployee(id).orElseThrow();
            });

    assertThat(result.getCompanyId()).isEqualTo(TENANT_A);
    assertThat(result.getCreatedBy()).isEqualTo(ACTOR_A);
    assertThat(result.getCreatedAt()).isNotNull();
    assertThat(result.getStatus().name()).isEqualTo("ACTIVE");
  }
}
