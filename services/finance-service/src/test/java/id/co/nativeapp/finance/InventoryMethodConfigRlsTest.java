package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers RLS proof for V53's {@code inventory_method_config} table (ADR 0067 §5 — the
 * finance-owned per-company perpetual-inventory election). No Java writer/entity exists yet (Phase
 * 0 ships ONLY the table + the policy; Phase D wires the console activation flow), so this proves
 * the policy directly over raw JDBC — the same technique {@link GlTenancyIsolationTest} uses for
 * {@code journal_entry}/{@code journal_line}: bind {@code app.current_tenant} on a plain {@code
 * app_user} connection (no superuser BYPASSRLS) and show tenant B never sees tenant A's row.
 */
@SpringBootTest
class InventoryMethodConfigRlsTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "dddddddd-dddd-dddd-dddd-ddddddddddd1";
  private static final String TENANT_B = "dddddddd-dddd-dddd-dddd-ddddddddddd2";

  @Test
  void aTenantsInventoryMethodConfigRowIsInvisibleToAnotherTenant() throws Exception {
    UUID rowId = UUID.randomUUID();
    insertConfigRowAsTenant(rowId, TENANT_A);

    // Tenant A sees its own row.
    assertThat(countRowsAsTenant(TENANT_A))
        .as("tenant A must see its own election row")
        .isEqualTo(1L);
    assertThat(perpetualActiveAsTenant(rowId, TENANT_A))
        .as("column default TRUE per ADR 0067 §5 (inert in Phase 0 — nothing reads it yet)")
        .isTrue();

    // Tenant B sees NOTHING — FORCE RLS blocks A's row even though B queries the same table.
    assertThat(countRowsAsTenant(TENANT_B))
        .as("tenant B must NOT see tenant A's inventory_method_config row (FORCE RLS)")
        .isZero();

    // The admin (BYPASSRLS) connection proves the row genuinely persisted (not silently dropped).
    assertThat(countRowsAsAdmin()).as("the row exists in the table").isEqualTo(1L);
  }

  @Test
  void aTenantCannotInsertARowStampedWithAnotherTenantsCompanyId() {
    // WITH CHECK enforces the same conjunction on write: an app_user session bound to TENANT_A
    // cannot INSERT a row whose company_id claims to be TENANT_B (a forged/mismatched tenant
    // stamp), exactly mirroring every other FORCE-RLS table's WITH CHECK proof in this suite.
    UUID rowId = UUID.randomUUID();
    assertThatThrownBy(() -> insertConfigRowStampedAs(rowId, TENANT_A, TENANT_B))
        .as("WITH CHECK must reject a row whose company_id does not match the bound session tenant")
        .isInstanceOf(java.sql.SQLException.class);
  }

  private void insertConfigRowAsTenant(UUID id, String tenant) throws Exception {
    insertConfigRowStampedAs(id, tenant, tenant);
  }

  /**
   * Binds {@code app.current_tenant = sessionTenant} then INSERTs a row stamped {@code company_id =
   * stampedCompanyId}. When the two differ, {@code WITH CHECK} must reject the write.
   */
  private void insertConfigRowStampedAs(UUID id, String sessionTenant, String stampedCompanyId)
      throws Exception {
    try (Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + sessionTenant + "'");
      // perpetual_active is deliberately OMITTED — relies on the column DEFAULT TRUE (V53) so this
      // test also proves the default, not merely a value this test chose to insert.
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO inventory_method_config"
                  + " (id, method, cutover_period, activated_at,"
                  + "  created_at, created_by, updated_at, updated_by, version, company_id)"
                  + " VALUES (?, 'PERPETUAL', NULL, NULL,"
                  + "  now(), 'test-actor', now(), 'test-actor', 0, ?)")) {
        ps.setObject(1, id);
        ps.setString(2, stampedCompanyId);
        ps.executeUpdate();
      }
    }
  }

  private long countRowsAsTenant(String tenant) throws Exception {
    try (Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (ResultSet rs = set.executeQuery("SELECT count(*) FROM inventory_method_config")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private boolean perpetualActiveAsTenant(UUID id, String tenant) throws Exception {
    try (Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT perpetual_active FROM inventory_method_config WHERE id = ?")) {
        ps.setObject(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getBoolean(1);
        }
      }
    }
  }

  private long countRowsAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM inventory_method_config")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE inventory_method_config");
    } catch (Exception ignored) {
      // Table not yet created (pre-Flyway) — nothing to reset.
    }
  }
}
