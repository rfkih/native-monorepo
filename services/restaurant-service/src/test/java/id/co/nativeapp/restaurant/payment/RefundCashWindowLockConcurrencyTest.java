package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.VoidRefundService;
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
 * Deterministic proof that {@code VoidRefundWriter.refund} acquires the per-business {@code
 * CashWindowLock} SHARED mode ({@code CashWindowLock.acquireForCommit}) — the verified HIGH race
 * fix — as the FIRST lock-acquiring statement, BEFORE the {@code occurredAt} that becomes the
 * append-only {@code payment_refund} row's timestamp (the exact column the register close sums) is
 * captured.
 *
 * <p>Same technique as {@code RegisterCloseLockConcurrencyTest}/{@code
 * CheckoutCashWindowLockConcurrencyTest}: manually hold the EXCLUSIVE advisory lock a register
 * close would hold (simulating a close in progress) on a raw JDBC connection, and prove a
 * concurrent {@code refund()} — on another thread — BLOCKS for as long as that EXCLUSIVE lock is
 * held, then completes promptly once released.
 */
@SpringBootTest
class RefundCashWindowLockConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-refund-lock-test@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private VoidRefundService voidRefundService;

  @Test
  void refundBlocksUntilAManuallyHeldCashWindowLockIsReleased() throws Exception {
    // Full-refund amounts (2026-08-31 audit #2: the writer rejects partials at the edge).
    PaymentResponse checkoutPayment =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              UUID menuItemId =
                  menuService
                      .createItem(
                          new CreateMenuItemRequest(BUSINESS_ID, "Kopi", "DRINK", 10_000L, "IDR"))
                      .id();
              CheckoutRequest req =
                  new CheckoutRequest(
                      BUSINESS_ID,
                      "refund-lock-checkout-key",
                      List.of(new OrderLineRequest(menuItemId, 1)),
                      new PaymentRequest(TenderType.CASH, 20_000L));
              CheckoutResult result = orderService.checkout(req);
              return result.order().payment();
            });
    UUID paymentId = checkoutPayment.paymentId();

    // Hold the SAME advisory lock CashWindowLock.acquire() would take, on a separate raw
    // connection/transaction — exactly mirroring the SQL the fix runs.
    try (Connection lockHolder =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lockHolder.setAutoCommit(false);
      try (Statement st = lockHolder.createStatement()) {
        st.execute("SELECT pg_advisory_xact_lock(hashtext('cash_window:" + BUSINESS_ID + "'))");
      }

      CountDownLatch refundDone = new CountDownLatch(1);
      AtomicReference<PaymentResponse> refundResult = new AtomicReference<>();
      AtomicReference<Throwable> refundError = new AtomicReference<>();
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        pool.submit(
            () -> {
              try {
                PaymentResponse response =
                    TenantContext.callAs(
                        TENANT,
                        ACTOR,
                        () ->
                            voidRefundService.refund(
                                paymentId,
                                Money.ofMinor(checkoutPayment.amountMinor(), "IDR")));
                refundResult.set(response);
              } catch (Throwable t) {
                refundError.set(t);
              } finally {
                refundDone.countDown();
              }
            });

        // STILL BLOCKED: a short, bounded wait must NOT observe completion while the manual
        // holder keeps the lock.
        boolean finishedWhileLocked = refundDone.await(500, TimeUnit.MILLISECONDS);
        assertThat(finishedWhileLocked)
            .as("refund() must block while the CashWindowLock is held by another transaction")
            .isFalse();

        // Release the manually-held lock — refund() should now proceed.
        lockHolder.rollback();

        // UNBLOCKS: a generous bounded wait for completion.
        boolean finishedAfterRelease = refundDone.await(10, TimeUnit.SECONDS);
        assertThat(finishedAfterRelease)
            .as("refund() must complete promptly once the CashWindowLock is released")
            .isTrue();
      } finally {
        pool.shutdownNow();
      }

      assertThat(refundError.get()).as("refund() must not fail once unblocked").isNull();
      assertThat(refundResult.get()).isNotNull();
      assertThat(refundResult.get().status()).isEqualTo("REFUNDED");
    }
  }
}
