package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.domain.ConflictingLegalEmployerException;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests (b) + (c): the OrgUnit consumer hydrates the local org read model, and the
 * same-legal-employer concurrency invariant is enforced against it.
 *
 * <ul>
 *   <li><strong>(b)</strong> publish {@code OrgUnitCreated} → the {@code org_unit_projection} read
 *       model gains the org_unit's legal employer (consumed off a real Kafka broker);
 *   <li><strong>(c)</strong> two concurrent assignments under org units of the SAME legal employer
 *       succeed (and each emits an {@code AssignmentChanged}); a concurrent assignment under a
 *       DIFFERENT legal employer is rejected with {@link ConflictingLegalEmployerException} (→
 *       409).
 * </ul>
 */
@SpringBootTest
class OrgConsumeAndAssignmentInvariantTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";

  private static final UUID LEGAL_EMPLOYER_1 =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final UUID LEGAL_EMPLOYER_2 =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

  @Autowired private EmployeeService employeeService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private id.co.nativeapp.employee.org.service.OrgProjectionService orgProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void consumingOrgUnitCreatedPopulatesTheLocalReadModelWithItsLegalEmployer() {
    UUID orgUnitId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event =
        OrgUnitEventFixtures.created(orgUnitId, TENANT_A, LEGAL_EMPLOYER_1, "OUTLET");
    OrgUnitEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId.toString(), eventId, event);

    // The consumer is async; await the projection row (over the admin connection, since the table
    // is
    // FORCE RLS and an unscoped count would otherwise be 0).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(projectionCountAsAdmin()).isEqualTo(1L));

    Map<String, Object> row = projectionRowAsAdmin(orgUnitId);
    assertThat(row.get("legal_employer_id")).hasToString(LEGAL_EMPLOYER_1.toString());
    assertThat(row.get("company_id")).isEqualTo(TENANT_A);
    assertThat(row.get("type")).isEqualTo("OUTLET");
    assertThat(row.get("active")).isEqualTo(true);
  }

  @Test
  void consumingOrgUnitChangedUpdatesTheProjectionTypeAndActiveState() {
    UUID orgUnitId = UUID.randomUUID();
    // First a create, then a change (deactivation) for the same org unit.
    OrgUnitEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(),
        orgUnitId.toString(),
        UUID.randomUUID(),
        OrgUnitEventFixtures.created(orgUnitId, TENANT_A, LEGAL_EMPLOYER_1, "OUTLET"));
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(projectionCountAsAdmin()).isEqualTo(1L));

    OrgUnitEventFixtures.publishChanged(
        KAFKA.getBootstrapServers(),
        orgUnitId.toString(),
        UUID.randomUUID(),
        OrgUnitEventFixtures.changed(orgUnitId, TENANT_A, "OUTLET", "DEACTIVATED", false));

    // The change flips the projected active flag to false (idempotently applied).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(projectionRowAsAdmin(orgUnitId).get("active")).isEqualTo(false));
    // The legal employer is retained across the change (OrgUnitChanged does not restate it).
    assertThat(projectionRowAsAdmin(orgUnitId).get("legal_employer_id"))
        .hasToString(LEGAL_EMPLOYER_1.toString());
  }

  @Test
  void twoConcurrentAssignmentsUnderTheSameLegalEmployerSucceedAndEachEmitsAssignmentChanged() {
    // Two org units under the SAME legal employer, hydrated into the read model directly (the
    // consumer path is proven separately; here we seed the projection to keep the test focused).
    UUID orgUnitA = seedOrgUnit(LEGAL_EMPLOYER_1, "OUTLET");
    UUID orgUnitB = seedOrgUnit(LEGAL_EMPLOYER_1, "OUTLET");

    UUID employeeId = createEmployee();

    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 12, 31);

    Assignment first = addAssignment(employeeId, orgUnitA, "cashier", from, to);
    Assignment second =
        addAssignment(employeeId, orgUnitB, "shift_lead", from, to); // concurrent, same employer

    assertThat(first.getId()).isNotEqualTo(second.getId());

    // One AssignmentChanged per assignment in the outbox.
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT aggregate_id FROM outbox WHERE event_type = 'AssignmentChanged'"
                + " ORDER BY occurred_at, id");
    assertThat(events).hasSize(2);
    assertThat(events.stream().map(e -> e.get("aggregate_id")).toList())
        .containsExactlyInAnyOrder(first.getId().toString(), second.getId().toString());
  }

  @Test
  void aConcurrentAssignmentUnderADifferentLegalEmployerIsRejected() throws Exception {
    UUID orgUnitA = seedOrgUnit(LEGAL_EMPLOYER_1, "OUTLET");
    UUID orgUnitOther = seedOrgUnit(LEGAL_EMPLOYER_2, "OUTLET"); // a DIFFERENT legal employer

    UUID employeeId = createEmployee();
    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 12, 31);

    addAssignment(employeeId, orgUnitA, "cashier", from, to);

    // A concurrent assignment under a different legal employer must be rejected (→ 409).
    assertThatThrownBy(() -> addAssignment(employeeId, orgUnitOther, "cashier", from, to))
        .isInstanceOf(ConflictingLegalEmployerException.class);

    // Only the first assignment exists; only one AssignmentChanged was emitted. The assignment
    // table
    // is FORCE RLS, so count it over the admin (BYPASSRLS) connection (an unscoped app query is 0).
    assertThat(assignmentCountAsAdmin()).isEqualTo(1L);
    long events =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type = 'AssignmentChanged'", Long.class);
    assertThat(events).isEqualTo(1L);
  }

  @Test
  void anAssignmentUnderADifferentEmployerButNonConcurrentIsAllowed() {
    UUID orgUnitA = seedOrgUnit(LEGAL_EMPLOYER_1, "OUTLET");
    UUID orgUnitOther = seedOrgUnit(LEGAL_EMPLOYER_2, "OUTLET");

    UUID employeeId = createEmployee();
    // First assignment ends before the second begins -> NOT concurrent, so the different employer
    // is ok.
    addAssignment(
        employeeId, orgUnitA, "cashier", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
    Assignment later =
        addAssignment(
            employeeId,
            orgUnitOther,
            "cashier",
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 12, 31));
    assertThat(later.getId()).isNotNull();
  }

  @Test
  void anAssignmentForAnUnknownEmployeeIsRejectedWithNoRowAndNoEvent() throws Exception {
    UUID orgUnitA = seedOrgUnit(LEGAL_EMPLOYER_1, "OUTLET");
    UUID unknownEmployeeId = UUID.randomUUID(); // never created in this (or any) tenant

    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 12, 31);

    // The employee does not exist; RLS would NOT catch it (company_id is stamped from the caller's
    // tenant), so the writer's explicit existence guard must reject it → 404.
    assertThatThrownBy(() -> addAssignment(unknownEmployeeId, orgUnitA, "cashier", from, to))
        .isInstanceOf(EmployeeNotFoundException.class);

    // No assignment row was written, and NO AssignmentChanged event was emitted for the phantom.
    assertThat(assignmentCountAsAdmin()).isZero();
    long events =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type = 'AssignmentChanged'", Long.class);
    assertThat(events).isZero();
  }

  @Test
  void anAssignmentWithAReportingToThatIsNotAnInTenantEmployeeIsRejected() throws Exception {
    UUID orgUnitA = seedOrgUnit(LEGAL_EMPLOYER_1, "OUTLET");
    UUID employeeId = createEmployee();
    UUID unknownManager = UUID.randomUUID(); // not an employee in this tenant

    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 12, 31);

    // reporting_to pointing at a non-existent (or other-tenant) employee → rejected (400), no row,
    // no event.
    assertThatThrownBy(
            () ->
                addAssignmentReportingTo(employeeId, orgUnitA, unknownManager, "cashier", from, to))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(assignmentCountAsAdmin()).isZero();
    long events =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type = 'AssignmentChanged'", Long.class);
    assertThat(events).isZero();
  }

  @Test
  void anAssignmentWithAValidInTenantReportingToSucceeds() {
    UUID orgUnitA = seedOrgUnit(LEGAL_EMPLOYER_1, "OUTLET");
    UUID employeeId = createEmployee();
    UUID managerId = createEmployee(); // a real in-tenant employee acting as the manager

    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 12, 31);

    Assignment created =
        addAssignmentReportingTo(employeeId, orgUnitA, managerId, "cashier", from, to);
    assertThat(created.getId()).isNotNull();
    assertThat(created.getReportingTo()).isEqualTo(managerId);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private UUID createEmployee() {
    try {
      return TenantContext.callAs(
          TENANT_A,
          ACTOR_A,
          () ->
              employeeService
                  .create(
                      new CreateEmployeeCommand(
                          "Budi Santoso", "K0", "3200000000000000", "9999000011112222"))
                  .getId());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Assignment addAssignment(
      UUID employeeId, UUID orgUnitId, String role, LocalDate from, LocalDate to) {
    return addAssignmentReportingTo(employeeId, orgUnitId, null, role, from, to);
  }

  private Assignment addAssignmentReportingTo(
      UUID employeeId,
      UUID orgUnitId,
      UUID reportingTo,
      String role,
      LocalDate from,
      LocalDate to) {
    try {
      return TenantContext.callAs(
          TENANT_A,
          ACTOR_A,
          () ->
              assignmentService.add(
                  new AddAssignmentCommand(employeeId, orgUnitId, reportingTo, role, from, to)));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Seeds an org_unit_projection row via the CONSUMER service path ({@link
   * id.co.nativeapp.employee.org.service.OrgProjectionService#apply}), the same transactional write
   * the OrgUnit listener uses — so the auto-RLS aspect sets the GUC and the RLS {@code WITH CHECK}
   * passes (a bare repository save from a test would not engage the aspect).
   */
  private UUID seedOrgUnit(UUID legalEmployerId, String type) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT_A, legalEmployerId, type, true));
    return orgUnitId;
  }

  private long projectionCountAsAdmin() throws Exception {
    return countAsAdmin("org_unit_projection");
  }

  private long assignmentCountAsAdmin() throws Exception {
    return countAsAdmin("assignment");
  }

  private long countAsAdmin(String table) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Map<String, Object> projectionRowAsAdmin(UUID orgUnitId) {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT legal_employer_id, company_id, type, active FROM org_unit_projection"
                    + " WHERE org_unit_id = ?")) {
      ps.setObject(1, orgUnitId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Map.of(
            "legal_employer_id", rs.getObject("legal_employer_id"),
            "company_id", rs.getString("company_id"),
            "type", rs.getString("type"),
            "active", rs.getBoolean("active"));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
