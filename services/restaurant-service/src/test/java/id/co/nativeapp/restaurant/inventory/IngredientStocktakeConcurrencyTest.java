package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeResult;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeStockRacedException;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Idempotency under concurrency (ENGINEERING-STANDARDS §3.2) for the ingredient stocktake write
 * (ADR 0046 phase 1). Two threads submit the SAME {@code (company_id, idempotency_key)} at once
 * against the same costed ingredient; both pass the writer's idempotency probe and race the INSERT.
 * Exactly ONE must win — one {@code ingredient_stocktake}, one {@code ingredient_stocktake_line},
 * one {@code StocktakeCompleted} outbox row — and the ingredient's stock must be adjusted exactly
 * ONCE. Mirrors {@code StocktakeConcurrencyTest}.
 */
@SpringBootTest
class IngredientStocktakeConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-ingredient-race@example.co.id";
  private static final UUID OUTLET = UUID.fromString("66666666-6666-6666-6666-666666666666");

  @Autowired private IngredientStocktakeService service;

  @Test
  void twoConcurrentSubmitsWithTheSameKeyPostExactlyOneIngredientStocktake() throws Exception {
    UUID ingredientId = seedIngredient(20, 7_500L);
    String key = "race-ingredient-stocktake-key";

    CyclicBarrier startLine = new CyclicBarrier(2);
    List<SubmitIngredientStocktakeResult> results = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Runnable submit =
          () -> {
            try {
              startLine.await(5, TimeUnit.SECONDS); // both fire together to maximise the race
              results.add(
                  TenantContext.callAs(
                      TENANT,
                      ACTOR,
                      () ->
                          service.submit(
                              new SubmitIngredientStocktakeRequest(
                                  OUTLET,
                                  List.of(new IngredientStocktakeLineInput(ingredientId, 18))),
                              key)));
            } catch (Throwable t) {
              errors.add(t);
            }
          };
      pool.submit(submit);
      pool.submit(submit);
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    // The only tolerated error is the retryable stock-raced conflict (the loser observing the
    // version bump before the unique constraint) — never anything else.
    assertThat(errors)
        .allSatisfy(t -> assertThat(t).isInstanceOf(StocktakeStockRacedException.class));

    // Exactly one stocktake, one line, one event — no double post regardless of the loser path.
    assertThat(rowCountAsAdmin("ingredient_stocktake")).isEqualTo(1);
    assertThat(rowCountAsAdmin("ingredient_stocktake_line")).isEqualTo(1);
    assertThat(rowCountAsAdmin("outbox")).isEqualTo(1);
    // Stock adjusted exactly once — to the physical count, never double-applied.
    assertThat(stockOf(ingredientId)).isEqualTo(18);

    // Exactly one thread created the stocktake; any successful loser reports created=false.
    long created = results.stream().filter(SubmitIngredientStocktakeResult::created).count();
    assertThat(created).isEqualTo(1);
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private int stockOf(UUID ingredientId) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_qty FROM ingredient WHERE id = '" + ingredientId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private UUID seedIngredient(int stock, long unitCostMinor) throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty, unit_cost_minor,"
                    + " cost_currency, active, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id)"
                    + " VALUES (?, ?, 'Patty', 'pcs', ?, ?, 'IDR', true, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
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
