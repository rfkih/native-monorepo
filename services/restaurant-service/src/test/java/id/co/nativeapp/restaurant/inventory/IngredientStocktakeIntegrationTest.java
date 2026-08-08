package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeResult;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
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
 * Testcontainers proof of ADR 0046 phase 1 against real Postgres: a submitted ingredient stocktake
 * adjusts the ingredient's stock to the physical count, persists an {@code ingredient_stocktake} +
 * {@code ingredient_stocktake_line} (V31 tables, RLS insert), computes the valued net shrinkage,
 * writes EXACTLY ONE outbox row for a costed submission, an idempotent replay does not re-adjust or
 * emit a second event, and an all-uncosted submission writes ZERO outbox rows.
 */
@SpringBootTest
class IngredientStocktakeIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-ingredient-stocktake@example.co.id";
  private static final UUID OUTLET = UUID.fromString("88888888-8888-8888-8888-888888888888");

  @Autowired private IngredientStocktakeService service;

  @Test
  void submitAdjustsStockValuesShrinkageAndWritesExactlyOneOutboxRow() throws Exception {
    // A costed ingredient: 20 in system at a 7.5k unit cost.
    UUID ingredientId = seedIngredient("Patty", "pcs", 20, 7_500L, "IDR");

    // Physical count = 18 → 2 units short → valued 15k shrinkage (loss).
    SubmitIngredientStocktakeResult result =
        asTenant(
            () ->
                service.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET, List.of(new IngredientStocktakeLineInput(ingredientId, 18))),
                    "ingredient-stocktake-key-1"));

    assertThat(result.created()).isTrue();
    assertThat(result.stocktake().shrinkageMinor()).isEqualTo(15_000L);
    assertThat(result.stocktake().currency()).isEqualTo("IDR");
    UUID stocktakeId = result.stocktake().id();

    try (Connection admin = admin()) {
      try (Statement st = admin.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT stock_qty FROM ingredient WHERE id = '" + ingredientId + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("stock_qty")).isEqualTo(18);
      }
      try (Statement st = admin.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT system_qty, counted_qty, variance_qty, variance_value_minor"
                      + " FROM ingredient_stocktake_line WHERE ingredient_stocktake_id = '"
                      + stocktakeId
                      + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("system_qty")).isEqualTo(20);
        assertThat(rs.getInt("counted_qty")).isEqualTo(18);
        assertThat(rs.getInt("variance_qty")).isEqualTo(-2);
        assertThat(rs.getLong("variance_value_minor")).isEqualTo(-15_000L);
        assertThat(rs.next()).as("exactly one line").isFalse();
      }
      assertThat(rowCount(admin, "outbox")).isEqualTo(1L);
    }
  }

  @Test
  void sameKeyReplayReturns200WithNoReAdjustAndNoSecondEvent() throws Exception {
    UUID ingredientId = seedIngredient("Bawang", "g", 10, 100L, "IDR");
    String key = "ingredient-stocktake-replay-key";

    SubmitIngredientStocktakeResult first =
        asTenant(
            () ->
                service.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET, List.of(new IngredientStocktakeLineInput(ingredientId, 8))),
                    key));
    assertThat(first.created()).isTrue();

    SubmitIngredientStocktakeResult replay =
        asTenant(
            () ->
                service.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET, List.of(new IngredientStocktakeLineInput(ingredientId, 8))),
                    key));

    assertThat(replay.created()).isFalse();
    assertThat(replay.stocktake().id()).isEqualTo(first.stocktake().id());

    try (Connection admin = admin()) {
      // Stock still at the physical count from the FIRST submit only — not re-adjusted.
      try (Statement st = admin.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT stock_qty FROM ingredient WHERE id = '" + ingredientId + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("stock_qty")).isEqualTo(8);
      }
      assertThat(rowCount(admin, "ingredient_stocktake")).isEqualTo(1L);
      assertThat(rowCount(admin, "outbox")).isEqualTo(1L);
    }
  }

  @Test
  void aSubmissionWithZeroCostedLinesWritesZeroOutboxRows() throws Exception {
    UUID ingredientId = seedIngredient("Selada", "g", 5, null, null);

    SubmitIngredientStocktakeResult result =
        asTenant(
            () ->
                service.submit(
                    new SubmitIngredientStocktakeRequest(
                        OUTLET, List.of(new IngredientStocktakeLineInput(ingredientId, 3))),
                    "ingredient-stocktake-no-cost-key"));

    assertThat(result.created()).isTrue();
    assertThat(result.stocktake().currency()).isNull();
    assertThat(result.stocktake().shrinkageMinor()).isZero();

    try (Connection admin = admin()) {
      assertThat(rowCount(admin, "ingredient_stocktake")).isEqualTo(1L);
      assertThat(rowCount(admin, "outbox")).isZero();
      try (Statement st = admin.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT stock_qty FROM ingredient WHERE id = '" + ingredientId + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("stock_qty")).isEqualTo(3);
      }
    }
  }

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private long rowCount(Connection admin, String table) throws Exception {
    try (Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Seeds an ingredient at OUTLET (raw JDBC as the RLS-bypassing superuser). */
  private UUID seedIngredient(
      String name, String unit, int stock, Long unitCostMinor, String costCurrency)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty, unit_cost_minor,"
                    + " cost_currency, active, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, true, now(), 'test', now(), 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, OUTLET);
      ps.setString(3, name);
      ps.setString(4, unit);
      ps.setInt(5, stock);
      if (unitCostMinor == null) {
        ps.setNull(6, java.sql.Types.BIGINT);
      } else {
        ps.setLong(6, unitCostMinor);
      }
      ps.setString(7, costCurrency);
      ps.setString(8, TENANT);
      ps.executeUpdate();
    }
    return id;
  }
}
