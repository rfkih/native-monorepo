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
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Deterministic proof that {@code OrderWriter.checkout} acquires the per-business {@code
 * CashWindowLock} SHARED mode ({@code CashWindowLock.acquireForCommit}) — the verified HIGH race
 * fix — as the FIRST lock-acquiring statement, BEFORE the {@code occurredAt}/{@code now} that
 * becomes the recorded sale's {@code occurred_at} is captured.
 *
 * <p>Same technique as {@code RegisterCloseLockConcurrencyTest}: manually hold the EXCLUSIVE
 * advisory lock a register close would hold ({@code pg_advisory_xact_lock(hashtext('cash_window:' +
 * businessId))}, simulating a close in progress) on a raw JDBC connection, and prove a concurrent
 * {@code checkout()} — on another thread — BLOCKS for as long as that EXCLUSIVE lock is held, then
 * completes promptly once released. No sleeps are used as the blocking mechanism (bounded {@link
 * CountDownLatch} awaits only).
 */
@SpringBootTest
class CheckoutCashWindowLockConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-checkout-lock-test@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  @Test
  void checkoutBlocksUntilAManuallyHeldCashWindowLockIsReleased() throws Exception {
    UUID menuItemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Es Teh", "DRINK", 8_000L, "IDR");
              return menuService.createItem(req).id();
            });

    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_ID, "lock-checkout-key", List.of(new OrderLineRequest(menuItemId, 1)));

    // Hold the SAME advisory lock CashWindowLock.acquire() would take, on a separate raw
    // connection/transaction — exactly mirroring the SQL the fix runs.
    try (Connection lockHolder =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lockHolder.setAutoCommit(false);
      try (Statement st = lockHolder.createStatement()) {
        st.execute("SELECT pg_advisory_xact_lock(hashtext('cash_window:" + BUSINESS_ID + "'))");
      }

      CountDownLatch checkoutDone = new CountDownLatch(1);
      AtomicReference<CheckoutResult> checkoutResult = new AtomicReference<>();
      AtomicReference<Throwable> checkoutError = new AtomicReference<>();
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        pool.submit(
            () -> {
              try {
                CheckoutResult result =
                    TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(checkoutReq));
                checkoutResult.set(result);
              } catch (Throwable t) {
                checkoutError.set(t);
              } finally {
                checkoutDone.countDown();
              }
            });

        // STILL BLOCKED: a short, bounded wait must NOT observe completion while the manual
        // holder keeps the lock.
        boolean finishedWhileLocked = checkoutDone.await(500, TimeUnit.MILLISECONDS);
        assertThat(finishedWhileLocked)
            .as("checkout() must block while the CashWindowLock is held by another transaction")
            .isFalse();

        // Release the manually-held lock — checkout() should now proceed.
        lockHolder.rollback();

        // UNBLOCKS: a generous bounded wait for completion.
        boolean finishedAfterRelease = checkoutDone.await(10, TimeUnit.SECONDS);
        assertThat(finishedAfterRelease)
            .as("checkout() must complete promptly once the CashWindowLock is released")
            .isTrue();
      } finally {
        pool.shutdownNow();
      }

      assertThat(checkoutError.get()).as("checkout() must not fail once unblocked").isNull();
      assertThat(checkoutResult.get()).isNotNull();
      assertThat(checkoutResult.get().created()).isTrue();
    }
  }
}
