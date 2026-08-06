package id.co.nativeapp.restaurant.stocktake;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeLineInput;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeResult;
import id.co.nativeapp.restaurant.stocktake.service.StocktakeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof of ADR 0038 phase 3 against real Postgres: a submitted stocktake adjusts the
 * menu item's stock to the physical count, persists a {@code stocktake} + {@code stocktake_line}
 * (V28 tables, RLS insert), and computes the valued net shrinkage. Read back over the admin
 * (RLS-bypassing) connection since the tables are FORCE RLS.
 */
@SpringBootTest
class StocktakeIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-stocktake@example.co.id";
  private static final UUID OUTLET = UUID.fromString("88888888-8888-8888-8888-888888888888");

  @Autowired private StocktakeService service;

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void submitAdjustsStockAndValuesShrinkage() throws Exception {
    // A tracked item: 20 in system at a 7.5k unit cost.
    UUID itemId = seedTrackedItem(20, 7_500L);

    // Physical count = 18 → 2 units short → valued 15k shrinkage (loss).
    SubmitStocktakeResult result =
        asTenant(
            () ->
                service.submit(
                    new SubmitStocktakeRequest(OUTLET, List.of(new StocktakeLineInput(itemId, 18))),
                    "stocktake-key-1"));

    assertThat(result.created()).isTrue();
    assertThat(result.stocktake().shrinkageMinor()).isEqualTo(15_000L);
    UUID stocktakeId = result.stocktake().id();

    try (Connection admin = admin()) {
      // Stock adjusted to the count.
      try (Statement st = admin.createStatement();
          ResultSet rs =
              st.executeQuery("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("stock_quantity")).isEqualTo(18);
      }
      // A reconciliation line persisted with the valued variance.
      try (Statement st = admin.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT system_qty, counted_qty, variance_qty, variance_value_minor"
                      + " FROM stocktake_line WHERE stocktake_id = '"
                      + stocktakeId
                      + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("system_qty")).isEqualTo(20);
        assertThat(rs.getInt("counted_qty")).isEqualTo(18);
        assertThat(rs.getInt("variance_qty")).isEqualTo(-2);
        assertThat(rs.getLong("variance_value_minor")).isEqualTo(-15_000L);
        assertThat(rs.next()).as("exactly one line").isFalse();
      }
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Seeds a tracked, costed menu item at OUTLET (raw JDBC as the RLS-bypassing superuser). */
  private UUID seedTrackedItem(int stock, long unitCostMinor) throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO menu_item (id, business_id, name, category, price_minor, currency,"
                    + " active, available, stock_quantity, unit_cost_minor, created_at, created_by,"
                    + " updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, 'Nasi Goreng', 'Food', 25000, 'IDR', true, true, ?, ?,"
                    + " now(), 'test', now(), 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, OUTLET);
      ps.setInt(3, stock);
      ps.setLong(4, unitCostMinor);
      ps.setString(5, TENANT);
      ps.executeUpdate();
    }
    return id;
  }
}
