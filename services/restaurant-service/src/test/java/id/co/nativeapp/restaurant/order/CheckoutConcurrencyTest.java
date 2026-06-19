package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrency (W1) for the checkout path, mirroring {@link
 * id.co.nativeapp.restaurant.RecordSaleConcurrencyTest}.
 *
 * <p>Two threads attempt to check out the same {@code idempotencyKey} at the same time, on a {@link
 * CyclicBarrier}. Exactly one {@code restaurant_order} row, exactly one {@code sale} row, and
 * exactly one {@code SaleRecorded} outbox row must exist after both calls complete. Both callers
 * must receive a successful idempotent result — no exception and no 500 — with exactly one {@code
 * created=true} and one {@code created=false}, both resolving to the same order id.
 */
@SpringBootTest
class CheckoutConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void concurrentSameKeyYieldsOneOrderOneSaleOneEventAndTwoSuccessfulResults() throws Exception {
    // Arrange: create a menu item that both threads will use.
    UUID menuItemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Nasi Goreng", "MAIN", 15_000L, "IDR");
              return menuService.createItem(req).id();
            });

    String idempotencyKey = "race-order-key-456";
    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_ID, idempotencyKey, List.of(new OrderLineRequest(menuItemId, 2)));

    // A barrier so both threads enter checkout as close to simultaneously as possible,
    // maximising the chance both miss the idempotency short-circuit and race the INSERT
    // (the loser must then be recovered, not 500).
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<CheckoutResult> attempt =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return orderService.checkout(checkoutReq);
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<CheckoutResult> f1 = pool.submit(attempt);
      Future<CheckoutResult> f2 = pool.submit(attempt);

      // Neither call throws — both return a successful idempotent result.
      CheckoutResult r1 = f1.get();
      CheckoutResult r2 = f2.get();

      // Exactly one created=true and one created=false, both the same order id.
      assertThat(r1.created() ^ r2.created())
          .as("exactly one caller created the order, the other was idempotent")
          .isTrue();
      assertThat(r1.order().orderId())
          .as("both callers resolve to the same single order")
          .isEqualTo(r2.order().orderId());
    } finally {
      pool.shutdownNow();
    }

    // Exactly ONE restaurant_order row (admin/BYPASSRLS connection).
    assertThat(orderRowCountAsAdmin()).isEqualTo(1L);

    // Exactly ONE sale row.
    assertThat(saleRowCountAsAdmin()).isEqualTo(1L);

    // Exactly ONE SaleRecorded outbox row — the conflict path emitted nothing.
    Long outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'", Long.class);
    assertThat(outboxCount).isEqualTo(1L);
  }

  private long orderRowCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM restaurant_order")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long saleRowCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM sale")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
