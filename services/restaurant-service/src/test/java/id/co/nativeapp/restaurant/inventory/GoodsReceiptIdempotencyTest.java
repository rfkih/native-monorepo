package id.co.nativeapp.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.inventory.domain.GoodsReceiptIdempotencyKeyConflictException;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
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
 * ADR 0067 Phase D, D1 — receive idempotency, against a REAL Testcontainers Postgres: a duplicate
 * priced receive under the SAME {@code Idempotency-Key} adds value + emits {@code StockReceived}
 * exactly ONCE (closing ADR 0056 accepted-limitation #1); a DIFFERENT key is a genuine new receive;
 * the SAME key with a DIFFERENT payload is a 409; a call with no key at all is unaffected (backward
 * compatible); and a concurrent same-key race (two DIFFERENT ingredients, so the ingredient
 * optimistic-lock version never collides — only the {@code goods_receipt} partial-unique index
 * does) still leaves exactly one row + one outbox event, recovered via a FRESH-transaction re-read
 * (the {@code IngredientStocktakeService}/{@code RegisterSessionService} pattern).
 */
@SpringBootTest
class GoodsReceiptIdempotencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-goods-receipt-idempotency@example.co.id";
  private static final UUID OUTLET = UUID.fromString("77777777-7777-7777-7777-777777777777");

  @Autowired private IngredientService service;

  @Test
  void aDuplicateReceiveWithTheSameKeyAddsValueOnceEmitsOnceAndReturnsTheSameReceipt()
      throws Exception {
    UUID ingredientId = seedIngredient(10, 5_000L);
    String key = "receive-key-" + UUID.randomUUID();

    IngredientResponse first =
        TenantContext.callAs(
            TENANT, ACTOR, () -> service.addStock(ingredientId, 10, 130_000L, "IDR", key));
    IngredientResponse second =
        TenantContext.callAs(
            TENANT, ACTOR, () -> service.addStock(ingredientId, 10, 130_000L, "IDR", key));

    // Value added exactly once: 10 @ 5_000 (50_000) + 10 @ landed 130_000 => 20 units / 180_000.
    assertThat(first.stockQty()).isEqualTo(20);
    assertThat(first.stockValueMinor()).isEqualTo(180_000L);
    assertThat(second).isEqualTo(first);

    assertThat(rowCountAsAdmin("goods_receipt")).isEqualTo(1L);
    assertThat(rowCountAsAdmin("outbox")).isEqualTo(1L);
    assertThat(stockOf(ingredientId)).isEqualTo(20);
    assertThat(stockValueOf(ingredientId)).isEqualTo(180_000L);
  }

  @Test
  void aDifferentKeyIsAGenuinelyNewReceive() throws Exception {
    UUID ingredientId = seedIngredient(10, 5_000L);

    TenantContext.callAs(
        TENANT, ACTOR, () -> service.addStock(ingredientId, 10, 130_000L, "IDR", "receive-key-a"));
    TenantContext.callAs(
        TENANT, ACTOR, () -> service.addStock(ingredientId, 5, 65_000L, "IDR", "receive-key-b"));

    // Two independent receives both landed: 10 (50_000) + 10 (130_000) + 5 (65_000) = 25 units /
    // 245_000.
    assertThat(rowCountAsAdmin("goods_receipt")).isEqualTo(2L);
    assertThat(rowCountAsAdmin("outbox")).isEqualTo(2L);
    assertThat(stockOf(ingredientId)).isEqualTo(25);
    assertThat(stockValueOf(ingredientId)).isEqualTo(245_000L);
  }

  @Test
  void theSameKeyWithADifferentPayloadIsRejectedAsAConflict() throws Exception {
    UUID ingredientId = seedIngredient(10, 5_000L);
    String key = "receive-key-" + UUID.randomUUID();

    TenantContext.callAs(
        TENANT, ACTOR, () -> service.addStock(ingredientId, 10, 130_000L, "IDR", key));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    // Same key, different amountPaidMinor — a client bug, never a silent 200.
                    () -> service.addStock(ingredientId, 10, 999_000L, "IDR", key)))
        .isInstanceOf(GoodsReceiptIdempotencyKeyConflictException.class);

    // The conflicting call added nothing — still exactly the first receive's effect.
    assertThat(rowCountAsAdmin("goods_receipt")).isEqualTo(1L);
    assertThat(stockOf(ingredientId)).isEqualTo(20);
    assertThat(stockValueOf(ingredientId)).isEqualTo(180_000L);
  }

  @Test
  void aNoKeyReceiveIsUnaffectedAndKeepsTodaysBehaviour() throws Exception {
    // Backward compatibility (pre-D1 callers): no Idempotency-Key at all still receives normally,
    // with idempotency_key NULL — the D1 dedup is opt-in until a caller supplies a key.
    UUID ingredientId = seedIngredient(10, 5_000L);

    TenantContext.callAs(
        TENANT, ACTOR, () -> service.addStock(ingredientId, 10, 130_000L, "IDR", null));

    assertThat(rowCountAsAdmin("goods_receipt")).isEqualTo(1L);
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT idempotency_key FROM goods_receipt")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isNull();
    }
  }

  @Test
  void twoConcurrentFirstSeenReceivesSharingAKeyRaceToExactlyOneReceipt() throws Exception {
    // Two DIFFERENT ingredients (a client-bug/replay-storm key reuse) so the two REQUIRES_NEW
    // transactions never collide on the SAME ingredient row's optimistic-lock version — only the
    // goods_receipt (company_id, idempotency_key) partial-unique index decides the race, exactly
    // exercising IngredientService#addStock's DataIntegrityViolationException recovery.
    UUID ingredientA = seedIngredient(10, 5_000L);
    UUID ingredientB = seedIngredient(10, 5_000L);
    String key = "race-receive-key-" + UUID.randomUUID();

    CyclicBarrier startLine = new CyclicBarrier(2);
    List<IngredientResponse> results = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Runnable submitA =
          () -> {
            try {
              startLine.await(5, TimeUnit.SECONDS);
              results.add(
                  TenantContext.callAs(
                      TENANT,
                      ACTOR,
                      () -> service.addStock(ingredientA, 10, 130_000L, "IDR", key)));
            } catch (Throwable t) {
              errors.add(t);
            }
          };
      Runnable submitB =
          () -> {
            try {
              startLine.await(5, TimeUnit.SECONDS);
              results.add(
                  TenantContext.callAs(
                      TENANT,
                      ACTOR,
                      () -> service.addStock(ingredientB, 10, 130_000L, "IDR", key)));
            } catch (Throwable t) {
              errors.add(t);
            }
          };
      pool.submit(submitA);
      pool.submit(submitB);
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    // Two valid interleavings, and the invariant below (one receipt/event, value in exactly one
    // ingredient) holds for BOTH — so tolerate either rather than assuming a guaranteed race:
    //  - TRUE race: both REQUIRES_NEW txs attempt the INSERT; the loser hits the (company_id,
    //    idempotency_key) unique index, recovers via the DataIntegrityViolation re-read, and both
    //    calls return (2 results, 0 errors);
    //  - SERIALIZED (winner commits before the loser's replay probe runs): the loser's probe finds
    //    the winner's receipt under a DIFFERENT ingredient_id → a legitimate 409 conflict (the
    //    same-key/different-payload contract), so 1 result + 1 conflict error.
    if (errors.isEmpty()) {
      assertThat(results).as("true race: both calls recovered via the unique-index re-read").hasSize(2);
    } else {
      assertThat(results).as("serialized: the winner returned, the loser conflicted").hasSize(1);
      assertThat(errors)
          .as("the only tolerated error is the same-key/different-ingredient 409")
          .singleElement()
          .isInstanceOf(GoodsReceiptIdempotencyKeyConflictException.class);
    }

    // Exactly one receipt/event system-wide, regardless of which ingredient won the race.
    assertThat(rowCountAsAdmin("goods_receipt")).isEqualTo(1L);
    assertThat(rowCountAsAdmin("outbox")).isEqualTo(1L);

    // The landed value moved into exactly ONE of the two ingredients — summed across both, the
    // total is deterministic even though the winner is not.
    long totalQty = stockOf(ingredientA) + stockOf(ingredientB);
    long totalValue = stockValueOf(ingredientA) + stockValueOf(ingredientB);
    assertThat(totalQty).as("10+10 seeded plus ONE 10-unit receive").isEqualTo(30L);
    assertThat(totalValue).as("50_000+50_000 seeded plus ONE 130_000 receive").isEqualTo(230_000L);
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

  private long stockValueOf(UUID ingredientId) throws Exception {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT stock_value_minor FROM ingredient WHERE id = '" + ingredientId + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private UUID seedIngredient(int stock, long unitCostMinor) throws Exception {
    // A fresh name per call — the V31 active-name-per-outlet unique index would otherwise reject a
    // second "Patty" row for the same tenant/outlet (the two-ingredient concurrency test seeds
    // two).
    return seedIngredient(stock, unitCostMinor, "Patty " + UUID.randomUUID());
  }

  private UUID seedIngredient(int stock, long unitCostMinor, String name) throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO ingredient (id, business_id, name, unit, stock_qty,"
                    + " stock_value_minor, unit_cost_minor, cost_currency, active, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'pcs', ?, ?, ?, 'IDR', true, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, OUTLET);
      ps.setString(3, name);
      ps.setInt(4, stock);
      ps.setLong(5, (long) stock * unitCostMinor);
      ps.setLong(6, unitCostMinor);
      ps.setString(7, TENANT);
      ps.executeUpdate();
    }
    return id;
  }
}
