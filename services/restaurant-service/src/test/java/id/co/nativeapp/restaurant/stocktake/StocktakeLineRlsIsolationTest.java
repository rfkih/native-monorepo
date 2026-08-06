package id.co.nativeapp.restaurant.stocktake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeLineInput;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.service.StocktakeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Security gate for ADR 0038 phase 3: the RLS-bypass proof that the new FORCE-RLS stocktake and
 * stocktake_line tables (V28) isolate tenants. StocktakeIntegrationTest reads rows back over the
 * admin/BYPASSRLS connection (proving the insert), which cannot show isolation; this test connects
 * as the UNPRIVILEGED app_user role (no superuser, no BYPASSRLS; FORCE ROW LEVEL SECURITY binds
 * even the owner) and proves, on the real V28 policies:
 *
 * <ol>
 *   <li>With the session GUC set to tenant A, A's stocktake + line ARE visible.
 *   <li>With the GUC set to tenant B, A's stocktake + line are INVISIBLE (count 0).
 *   <li>WITH CHECK REJECTS planting a stocktake_line stamped company_id = A while the GUC is B —
 *       even referencing A's real stocktake id, because the FK check bypasses RLS.
 *   <li>The same WITH CHECK guard holds for the parent stocktake table.
 * </ol>
 */
@SpringBootTest
class StocktakeLineRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final UUID OUTLET_A = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private StocktakeService service;

  @Test
  void tenantBCannotReadTenantAsStocktakeAndCannotPlantALine() throws Exception {
    UUID itemId = seedTrackedItem(20, 7_500L);
    UUID stocktakeId =
        callAsA(
                () ->
                    service.submit(
                        new SubmitStocktakeRequest(
                            OUTLET_A, List.of(new StocktakeLineInput(itemId, 18))),
                        "rls-stocktake-key-1"))
            .stocktake()
            .id();

    assertThat(rowCount("stocktake", "id", stocktakeId, TENANT_A))
        .as("tenant A sees its own stocktake header under RLS")
        .isEqualTo(1L);
    assertThat(rowCount("stocktake_line", "stocktake_id", stocktakeId, TENANT_A))
        .as("tenant A sees its own stocktake_line under RLS")
        .isEqualTo(1L);

    assertThat(rowCount("stocktake", "id", stocktakeId, TENANT_B))
        .as("tenant B cannot read tenant A's stocktake header")
        .isZero();
    assertThat(rowCount("stocktake_line", "stocktake_id", stocktakeId, TENANT_B))
        .as("tenant B cannot read tenant A's stocktake_line rows")
        .isZero();

    assertThatThrownBy(() -> plantLineAsWrongTenant(stocktakeId, itemId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");

    assertThatThrownBy(this::plantStocktakeAsWrongTenant)
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");

    assertThat(rowCount("stocktake_line", "stocktake_id", stocktakeId, TENANT_A)).isEqualTo(1L);
  }

  private long rowCount(String table, String column, UUID value, String tenant)
      throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + tenant + "'");
      try (ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM " + table + " WHERE " + column + " = '" + value + "'")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void plantLineAsWrongTenant(UUID stocktakeId, UUID menuItemId) throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + TENANT_B + "'");
      st.executeUpdate(
          "INSERT INTO stocktake_line ("
              + "id, stocktake_id, menu_item_id, system_qty, counted_qty, variance_qty,"
              + " unit_cost_minor, variance_value_minor, created_at, created_by, updated_at,"
              + " updated_by, version, company_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + stocktakeId
              + "', '"
              + menuItemId
              + "', 5, 0, -5, 1000, -5000, now(), 'attacker', now(), 'attacker', 0, '"
              + TENANT_A
              + "')");
    }
  }

  private void plantStocktakeAsWrongTenant() throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + TENANT_B + "'");
      st.executeUpdate(
          "INSERT INTO stocktake ("
              + "id, business_id, counted_at, currency, shrinkage_minor, idempotency_key,"
              + " created_at, created_by, updated_at, updated_by, version, company_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + OUTLET_A
              + "', now(), 'IDR', 5000, 'attacker-key', now(), 'attacker', now(), 'attacker', 0, '"
              + TENANT_A
              + "')");
    }
  }

  private UUID seedTrackedItem(int stock, long unitCostMinor) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO menu_item (id, business_id, name, category, price_minor, currency,"
                    + " active, available, stock_quantity, unit_cost_minor, created_at, created_by,"
                    + " updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, 'Nasi Goreng', 'Food', 25000, 'IDR', true, true, ?, ?,"
                    + " now(), 'test', now(), 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, OUTLET_A);
      ps.setInt(3, stock);
      ps.setLong(4, unitCostMinor);
      ps.setString(5, TENANT_A);
      ps.executeUpdate();
    }
    return id;
  }

  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "app_secret");
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
