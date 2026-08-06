package id.co.nativeapp.restaurant.stocktake;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeStockRacedException;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Idempotency under concurrency (ENGINEERING-STANDARDS §3.2) for the stocktake write (ADR 0038
 * phase 3). Two threads submit the SAME {@code (company_id, idempotency_key)} at once against the
 * same tracked item; both pass the writer's idempotency probe and race the INSERT. Exactly ONE must
 * win — one {@code stocktake}, one {@code stocktake_line}, one {@code StocktakeCompleted} outbox
 * row — and the item's stock must be adjusted exactly ONCE (to the count, not double-applied). The
 * loser trips {@code uq_stocktake_company_idempotency} and is recovered by {@link StocktakeService}
 * in a fresh transaction with {@code created=false} (or, if a concurrent version bump is observed
 * first, surfaces the retryable {@link StocktakeStockRacedException}); either way NOTHING is
 * double-posted. A double-run here means double stock adjustment + double shrinkage — a money bug.
 */
@SpringBootTest
class StocktakeConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-race@example.co.id";
  private static final UUID OUTLET = UUID.fromString("66666666-6666-6666-6666-666666666666");

  @Autowired private StocktakeService service;

  @Test
  void twoConcurrentSubmitsWithTheSameKeyPostExactlyOneStocktake() throws Exception {
    UUID itemId = seedTrackedItem(20, 7_500L);
    String key = "race-stocktake-key";

    CyclicBarrier startLine = new CyclicBarrier(2);
    List<SubmitStocktakeResult> results = new CopyOnWriteArrayList<>();
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
                              new SubmitStocktakeRequest(
                                  OUTLET, List.of(new StocktakeLineInput(itemId, 18))),
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
    assertThat(rowCountAsAdmin("stocktake")).isEqualTo(1);
    assertThat(rowCountAsAdmin("stocktake_line")).isEqualTo(1);
    assertThat(rowCountAsAdmin("outbox")).isEqualTo(1);
    // Stock adjusted exactly once — to the physical count, never 16 (double-applied) or 20 (never).
    assertThat(stockOf(itemId)).isEqualTo(18);

    // Exactly one thread created the stocktake; any successful loser reports created=false.
    long created = results.stream().filter(SubmitStocktakeResult::created).count();
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

  private int stockOf(UUID itemId) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT stock_quantity FROM menu_item WHERE id = '" + itemId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

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
