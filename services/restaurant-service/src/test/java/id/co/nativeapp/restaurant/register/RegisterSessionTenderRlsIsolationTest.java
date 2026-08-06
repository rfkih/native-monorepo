package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.TenderCount;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Security gate for ADR 0038 phase 2: the RLS-bypass proof that the new FORCE-RLS {@code
 * register_session_tender} table isolates tenants. Unlike {@link
 * RegisterDailyClosePhase2IntegrationTest} (which reads the row back over the admin/BYPASSRLS
 * connection to prove the insert), this test connects as the UNPRIVILEGED {@code app_user} role
 * (the role Flyway/Hibernate/every repository use — no superuser, no BYPASSRLS, and the table's
 * {@code FORCE ROW LEVEL SECURITY} binds even the owner) and proves, on the real table policy:
 *
 * <ol>
 *   <li>With the session GUC set to tenant A, A's reconciliation line IS visible.
 *   <li>With the GUC set to tenant B, A's line is INVISIBLE (count 0) — B cannot read A's tender
 *       data (the core cross-tenant read-isolation proof).
 *   <li>The {@code WITH CHECK} clause REJECTS an insert that stamps {@code company_id} = A while
 *       the GUC is B — a row can never be planted under another tenant, even referencing A's real
 *       session id (foreign-key checks bypass RLS, so WITH CHECK is the only thing that stops it).
 * </ol>
 */
@SpringBootTest
class RegisterSessionTenderRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final UUID OUTLET_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final LocalDate DAY = LocalDate.of(2026, 8, 6);

  @Autowired private RegisterSessionService service;

  @Test
  void tenantBCannotReadTenantAsTenderLineAndCannotPlantOne() throws Exception {
    // Tenant A opens + closes a session with a CARD tender → one register_session_tender row.
    UUID sessionId =
        callAsA(
            () ->
                service
                    .open(
                        new OpenSessionRequest(OUTLET_A, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    callAsA(
        () ->
            service.close(
                sessionId,
                new CloseSessionRequest(0L, List.of(new TenderCount("CARD", 5_000L))),
                "close:" + sessionId));

    // (1) As the unprivileged app role, GUC = A → A's line is visible.
    assertThat(tenderLineCountForSession(sessionId, TENANT_A))
        .as("tenant A sees its own reconciliation line under RLS")
        .isEqualTo(1L);

    // (2) As the unprivileged app role, GUC = B → A's line is INVISIBLE (cross-tenant read
    // blocked).
    assertThat(tenderLineCountForSession(sessionId, TENANT_B))
        .as("tenant B cannot read tenant A's register_session_tender rows")
        .isZero();

    // (3) WITH CHECK blocks planting a row stamped company_id = A while scoped to tenant B — even
    // though the FK to A's real session succeeds (RI checks bypass RLS), the policy rejects it.
    assertThatThrownBy(() -> plantTenderRowAsWrongTenant(sessionId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");

    // (4) The rejected plant left nothing behind — still exactly one line, still A's.
    assertThat(tenderLineCountForSession(sessionId, TENANT_A)).isEqualTo(1L);
  }

  /** Count register_session_tender rows for a session under a given tenant GUC as the app role. */
  private long tenderLineCountForSession(UUID sessionId, String tenant) throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + tenant + "'");
      try (ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM register_session_tender WHERE session_id = '"
                  + sessionId
                  + "'")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Attempt to insert a row stamped company_id = A while the session GUC is tenant B. */
  private void plantTenderRowAsWrongTenant(UUID sessionId) throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + TENANT_B + "'");
      st.executeUpdate(
          "INSERT INTO register_session_tender ("
              + "id, session_id, business_id, tender_type, expected_minor, counted_minor,"
              + " over_short_minor, currency, created_at, created_by, updated_at, updated_by,"
              + " version, company_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + sessionId
              + "', '"
              + OUTLET_A
              + "', 'QRIS', 0, 1000, 1000, 'IDR', now(), 'attacker', now(), 'attacker', 0, '"
              + TENANT_A
              + "')");
    }
  }

  /** A connection as the unprivileged, RLS-bound app role (never the BYPASSRLS superuser). */
  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "app_secret");
  }

  private static <T> T callAsA(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT_A, ACTOR_A, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
