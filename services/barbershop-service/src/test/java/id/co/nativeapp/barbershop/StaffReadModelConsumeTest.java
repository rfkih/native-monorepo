package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (d) — the local staff read model, driven end-to-end through Kafka.
 *
 * <p>Publishing an {@code EmployeeChanged} (the employee-service + Debezium shape: raw Avro bytes +
 * the {@code id} header) is consumed into the {@code staff} read model — the employee appears as
 * active. A following {@code AssignmentChanged} sets the employee's assigned org unit on the same
 * row. So the vertical knows its staff (rule 2 — a cached read model, never a sync call). The rows
 * are read over the admin/BYPASSRLS connection (the table is FORCE RLS). Awaitility awaits the
 * async consumption (no {@code Thread.sleep}).
 */
@SpringBootTest
class StaffReadModelConsumeTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String EMPLOYEE = "33333333-3333-3333-3333-333333333333";
  private static final String ORG_UNIT = "22222222-2222-2222-2222-222222222222";

  @Test
  void consumingEmployeeChangedThenAssignmentChangedBuildsTheStaffReadModel() throws Exception {
    // EmployeeChanged (ACTIVE) -> the staff row appears, active.
    EventFixtures.publishEmployeeChanged(
        KAFKA.getBootstrapServers(),
        EMPLOYEE,
        UUID.randomUUID(),
        EventFixtures.employeeChanged(EMPLOYEE, TENANT_A, "ACTIVE"));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(staffActiveAsAdmin(EMPLOYEE)).isEqualTo(Boolean.TRUE));

    // AssignmentChanged -> the same staff row now carries the assigned org unit.
    EventFixtures.publishAssignmentChanged(
        KAFKA.getBootstrapServers(),
        EMPLOYEE,
        UUID.randomUUID(),
        EventFixtures.assignmentChanged(
            "44444444-4444-4444-4444-444444444444", EMPLOYEE, TENANT_A, ORG_UNIT, "barber"));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(staffOrgUnitAsAdmin(EMPLOYEE)).isEqualTo(ORG_UNIT));

    // The employee is still active after the assignment (the kinds compose, neither clobbers the
    // other).
    assertThat(staffActiveAsAdmin(EMPLOYEE)).isEqualTo(Boolean.TRUE);
  }

  @Test
  void consumingAnInactiveEmployeeChangedMarksTheStaffInactive() throws Exception {
    EventFixtures.publishEmployeeChanged(
        KAFKA.getBootstrapServers(),
        EMPLOYEE,
        UUID.randomUUID(),
        EventFixtures.employeeChanged(EMPLOYEE, TENANT_A, "INACTIVE"));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(staffActiveAsAdmin(EMPLOYEE)).isEqualTo(Boolean.FALSE));
  }

  private Boolean staffActiveAsAdmin(String employeeId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement("SELECT active FROM staff WHERE employee_id = ?::uuid")) {
      ps.setString(1, employeeId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return rs.getBoolean(1);
      }
    }
  }

  private String staffOrgUnitAsAdmin(String employeeId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement("SELECT org_unit_id FROM staff WHERE employee_id = ?::uuid")) {
      ps.setString(1, employeeId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        Object orgUnit = rs.getObject(1);
        return orgUnit == null ? null : orgUnit.toString();
      }
    }
  }

  private java.sql.Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
