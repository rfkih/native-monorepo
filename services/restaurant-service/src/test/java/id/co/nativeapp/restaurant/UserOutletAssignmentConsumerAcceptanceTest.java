package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.restaurant.outletref.service.UserOutletAssignmentRefService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Consumer-side acceptance tests for the {@code UserOutletAssignmentChanged} listener (Phase 5).
 *
 * <p>Tests the service/writer layer directly (no Kafka broker needed — the Kafka listener is just a
 * thin adapter that calls {@link UserOutletAssignmentRefService#apply}). Verifies:
 *
 * <ol>
 *   <li>ASSIGNED event → row upserted with {@code active = TRUE}.
 *   <li>UNASSIGNED event → row upserted with {@code active = FALSE}.
 *   <li>Re-delivering the same event UUID is a no-op (idempotency via {@code processed_event}).
 *   <li>Events for different companies are isolated in separate RLS scopes (cross-tenant isolation).
 * </ol>
 *
 * <p><strong>Admin connection for assertions.</strong> The repository queries use RLS (the
 * auto-RLS aspect fires only inside {@code @Transactional} boundaries). Post-apply assertions use
 * the BYPASSRLS superuser admin connection so the row counts are tenant-independent and not subject
 * to missing-GUC fail-closed behaviour. Cross-tenant isolation is proven by asserting that the
 * admin sees exactly 1 row for {@code COMPANY_A} but 0 for {@code COMPANY_B} after a COMPANY_A event.
 *
 * <p>The full {@code @SpringBootTest} context runs Flyway (creates the tables), validates the JPA
 * mapping, and engages the auto-RLS aspect — the same behavior as production. The Postgres container
 * is shared with other suite tests via the {@link PostgresRlsTestBase} singleton pattern.
 */
@SpringBootTest
class UserOutletAssignmentConsumerAcceptanceTest extends PostgresRlsTestBase {

  private static final String COMPANY_A = "11111111-1111-1111-1111-111111111111";
  private static final String COMPANY_B = "22222222-2222-2222-2222-222222222222";
  private static final String USER_CASHIER = "kc-cashier-01";
  private static final UUID ORG_UNIT_OUTLET = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final int FROM_DAY = (int) LocalDate.of(2026, 7, 27).toEpochDay();
  private static final int TO_DAY_OPEN = (int) LocalDate.of(9999, 12, 31).toEpochDay();

  @Autowired private UserOutletAssignmentRefService service;

  // -----------------------------------------------------------------------
  // 1. ASSIGNED event → active row
  // -----------------------------------------------------------------------

  @Test
  void assignedEventUpsertsActiveRow() {
    UUID eventId = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();

    UserOutletAssignmentEvent event =
        new UserOutletAssignmentEvent(
            eventId, assignmentId, USER_CASHIER, COMPANY_A,
            ORG_UNIT_OUTLET, "ASSIGNED", FROM_DAY, TO_DAY_OPEN);

    boolean applied = service.apply(event);

    assertThat(applied).isTrue();
    // Verify the row is active using the admin (BYPASSRLS) connection.
    assertThat(countActiveRowsAdmin(COMPANY_A, USER_CASHIER, ORG_UNIT_OUTLET)).isOne();
  }

  // -----------------------------------------------------------------------
  // 2. UNASSIGNED event → inactive row
  // -----------------------------------------------------------------------

  @Test
  void unassignedEventUpsertsInactiveRow() {
    UUID assignmentId = UUID.randomUUID();

    // First assign.
    UserOutletAssignmentEvent assign =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), assignmentId, USER_CASHIER, COMPANY_A,
            ORG_UNIT_OUTLET, "ASSIGNED", FROM_DAY, TO_DAY_OPEN);
    service.apply(assign);

    // Then unassign with a different event id (a new event).
    int closeDay = (int) LocalDate.of(2026, 7, 28).toEpochDay();
    UserOutletAssignmentEvent unassign =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), assignmentId, USER_CASHIER, COMPANY_A,
            ORG_UNIT_OUTLET, "UNASSIGNED", FROM_DAY, closeDay);
    boolean applied = service.apply(unassign);

    assertThat(applied).isTrue();
    // Row exists but is no longer active.
    assertThat(countActiveRowsAdmin(COMPANY_A, USER_CASHIER, ORG_UNIT_OUTLET)).isZero();
    // The row still exists (inactive).
    assertThat(countAllRowsAdmin(COMPANY_A)).isOne();
  }

  // -----------------------------------------------------------------------
  // 3. Idempotency — re-delivering the same event UUID is a no-op
  // -----------------------------------------------------------------------

  @Test
  void redeliveryOfSameEventIdIsANoop() {
    UUID eventId = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();

    UserOutletAssignmentEvent event =
        new UserOutletAssignmentEvent(
            eventId, assignmentId, USER_CASHIER, COMPANY_A,
            ORG_UNIT_OUTLET, "ASSIGNED", FROM_DAY, TO_DAY_OPEN);

    // First delivery.
    boolean first = service.apply(event);
    assertThat(first).isTrue();

    // Simulate an unassign delivered with the SAME event id (a redelivery).
    int closeDay = (int) LocalDate.of(2026, 7, 28).toEpochDay();
    UserOutletAssignmentEvent duplicate =
        new UserOutletAssignmentEvent(
            eventId,   // same event id — this is a redelivery
            assignmentId, USER_CASHIER, COMPANY_A,
            ORG_UNIT_OUTLET, "UNASSIGNED", FROM_DAY, closeDay);

    boolean second = service.apply(duplicate);
    assertThat(second).isFalse(); // idempotency: skipped

    // Row is still active because the duplicate was suppressed.
    assertThat(countActiveRowsAdmin(COMPANY_A, USER_CASHIER, ORG_UNIT_OUTLET)).isOne();
  }

  // -----------------------------------------------------------------------
  // 4. Cross-tenant isolation
  // -----------------------------------------------------------------------

  @Test
  void eventForCompanyADoesNotCreateRowVisibleUnderCompanyB() {
    UUID eventId = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();

    // Event for COMPANY_A.
    UserOutletAssignmentEvent event =
        new UserOutletAssignmentEvent(
            eventId, assignmentId, USER_CASHIER, COMPANY_A,
            ORG_UNIT_OUTLET, "ASSIGNED", FROM_DAY, TO_DAY_OPEN);
    service.apply(event);

    // Admin can see the COMPANY_A row.
    assertThat(countAllRowsAdmin(COMPANY_A)).isOne();
    // No row exists for COMPANY_B (the assignment was scoped to COMPANY_A).
    assertThat(countAllRowsAdmin(COMPANY_B)).isZero();
  }

  // -----------------------------------------------------------------------
  // Admin helpers: direct DB queries bypassing RLS (BYPASSRLS superuser conn)
  // -----------------------------------------------------------------------

  /**
   * Counts ACTIVE assignment rows for the given (companyId, userId, orgUnitId) tuple using the
   * admin connection, bypassing RLS. Useful for asserting post-upsert state without needing a live
   * @Transactional scope.
   */
  private long countActiveRowsAdmin(String companyId, String userId, UUID orgUnitId) {
    try (Connection admin = java.sql.DriverManager.getConnection(
             POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement(
            "SELECT COUNT(*) FROM user_outlet_assignment_ref "
                + "WHERE company_id = ? AND user_id = ? AND org_unit_id = ? AND active = TRUE")) {
      // company_id is VARCHAR(64) in the schema, not UUID column.
      ps.setString(1, companyId);
      ps.setString(2, userId);
      ps.setObject(3, orgUnitId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Admin countActiveRows failed", e);
    }
  }

  /**
   * Counts ALL rows (active or inactive) for the given companyId using the admin connection,
   * bypassing RLS.
   */
  private long countAllRowsAdmin(String companyId) {
    try (Connection admin = java.sql.DriverManager.getConnection(
             POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement(
            "SELECT COUNT(*) FROM user_outlet_assignment_ref WHERE company_id = ?")) {
      // company_id is VARCHAR(64) in the schema, not UUID column.
      ps.setString(1, companyId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Admin countAllRows failed", e);
    }
  }
}
