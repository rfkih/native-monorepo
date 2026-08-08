package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
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
 * Security gate for ADR 0046 phase 1: the RLS-bypass proof that the new FORCE-RLS {@code
 * ingredient}, {@code ingredient_stocktake}, and {@code ingredient_stocktake_line} tables (V31)
 * isolate tenants. Mirrors {@code StocktakeLineRlsIsolationTest}. Connects as the UNPRIVILEGED
 * {@code app_user} role (no superuser, no BYPASSRLS; FORCE ROW LEVEL SECURITY binds even the owner)
 * and proves, on the real V31 policies:
 *
 * <ol>
 *   <li>With the session GUC set to tenant A, A's ingredient + stocktake + line ARE visible.
 *   <li>With the GUC set to tenant B, A's rows are INVISIBLE (count 0) on all three tables.
 *   <li>WITH CHECK REJECTS planting a row on any of the three tables stamped {@code company_id = A}
 *       while the GUC is B — even referencing A's real ids, because a FK check bypasses RLS.
 * </ol>
 */
@SpringBootTest
class IngredientInventoryRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-ingredient-a@example.co.id";
  private static final UUID OUTLET_A = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private IngredientStocktakeService service;

  @Test
  void tenantBCannotReadTenantAsIngredientCatalogRow() throws Exception {
    UUID ingredientId = seedIngredient(20, 7_500L);

    assertThat(rowCount("ingredient", "id", ingredientId, TENANT_A))
        .as("tenant A sees its own ingredient under RLS")
        .isEqualTo(1L);
    assertThat(rowCount("ingredient", "id", ingredientId, TENANT_B))
        .as("tenant B cannot read tenant A's ingredient")
        .isZero();

    assertThatThrownBy(() -> plantIngredientAsWrongTenant())
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");
  }

  @Test
  void tenantBCannotReadTenantAsIngredientStocktakeAndCannotPlantALine() throws Exception {
    UUID ingredientId = seedIngredient(20, 7_500L);
    UUID stocktakeId =
        callAsA(
                () ->
                    service.submit(
                        new SubmitIngredientStocktakeRequest(
                            OUTLET_A, List.of(new IngredientStocktakeLineInput(ingredientId, 18))),
                        "rls-ingredient-stocktake-key-1"))
            .stocktake()
            .id();

    assertThat(rowCount("ingredient_stocktake", "id", stocktakeId, TENANT_A))
        .as("tenant A sees its own ingredient stocktake header under RLS")
        .isEqualTo(1L);
    assertThat(
            rowCount("ingredient_stocktake_line", "ingredient_stocktake_id", stocktakeId, TENANT_A))
        .as("tenant A sees its own ingredient stocktake line under RLS")
        .isEqualTo(1L);

    assertThat(rowCount("ingredient_stocktake", "id", stocktakeId, TENANT_B))
        .as("tenant B cannot read tenant A's ingredient stocktake header")
        .isZero();
    assertThat(
            rowCount("ingredient_stocktake_line", "ingredient_stocktake_id", stocktakeId, TENANT_B))
        .as("tenant B cannot read tenant A's ingredient stocktake line rows")
        .isZero();

    assertThatThrownBy(() -> plantLineAsWrongTenant(stocktakeId, ingredientId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");

    assertThatThrownBy(this::plantStocktakeAsWrongTenant)
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("row-level security");

    assertThat(
            rowCount("ingredient_stocktake_line", "ingredient_stocktake_id", stocktakeId, TENANT_A))
        .isEqualTo(1L);
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

  private void plantIngredientAsWrongTenant() throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + TENANT_B + "'");
      st.executeUpdate(
          "INSERT INTO ingredient ("
              + "id, business_id, name, unit, stock_qty, active, created_at, created_by, updated_at,"
              + " updated_by, version, company_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + OUTLET_A
              + "', 'Attacker Bahan', 'pcs', 0, true, now(), 'attacker', now(), 'attacker', 0, '"
              + TENANT_A
              + "')");
    }
  }

  private void plantLineAsWrongTenant(UUID stocktakeId, UUID ingredientId) throws SQLException {
    try (Connection app = appConnection();
        Statement st = app.createStatement()) {
      st.execute("SET app.current_tenant = '" + TENANT_B + "'");
      st.executeUpdate(
          "INSERT INTO ingredient_stocktake_line ("
              + "id, ingredient_stocktake_id, ingredient_id, system_qty, counted_qty, variance_qty,"
              + " unit_cost_minor, variance_value_minor, created_at, created_by, updated_at,"
              + " updated_by, version, company_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + stocktakeId
              + "', '"
              + ingredientId
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
          "INSERT INTO ingredient_stocktake ("
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

  private UUID seedIngredient(int stock, long unitCostMinor) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty, unit_cost_minor,"
                    + " cost_currency, active, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id)"
                    + " VALUES (?, ?, 'Patty', 'pcs', ?, ?, 'IDR', true, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
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
