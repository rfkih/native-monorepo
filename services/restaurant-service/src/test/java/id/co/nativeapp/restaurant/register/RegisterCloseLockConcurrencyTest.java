package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
 * Deterministic proof that {@code RegisterSessionWriter.close} acquires the per-business {@code
 * CashWindowLock} EXCLUSIVE mode ({@code CashWindowLock.acquireForClose}) — the verified HIGH race
 * fix — BEFORE it computes {@code closeInstant}/the cash sums, and that it correctly waits for an
 * in-flight sale/refund's SHARED lock to release (the readers-writer contract).
 *
 * <p>Rather than racing two real concurrent operations (inherently non-deterministic — the exact
 * interleaving the money bug depends on is a narrow window), this test manually holds the SHARED
 * advisory lock a sale/refund commit would hold (via a raw JDBC connection, {@code
 * pg_advisory_xact_lock_shared(hashtext('cash_window:' + businessId))}, exactly the SQL {@code
 * CashWindowLock.acquireForCommit} executes) and proves {@code close()} — run on another thread —
 * BLOCKS for as long as that SHARED lock is held, then completes promptly once it is released. No
 * sleeps are used as the blocking mechanism: a {@link CountDownLatch} with a short bounded await
 * proves "still blocked", and a generous (10s) bounded await proves "unblocks after release"
 * without ever waiting the full timeout on the happy path.
 */
@SpringBootTest
class RegisterCloseLockConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-lock-test@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private RegisterSessionService registerSessionService;

  @Test
  void closeBlocksUntilAManuallyHeldCashWindowLockIsReleased() throws Exception {
    UUID sessionId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              OpenSessionResult opened =
                  registerSessionService.open(
                      new OpenSessionRequest(BUSINESS_ID, 100_000L, "IDR", null), "lock-open-key");
              return opened.session().id();
            });

    // Hold the SHARED advisory lock CashWindowLock.acquireForCommit() would take (simulating an
    // in-flight sale/refund), on a separate raw connection/transaction — exactly mirroring the SQL
    // the fix runs.
    try (Connection lockHolder =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lockHolder.setAutoCommit(false);
      try (Statement st = lockHolder.createStatement()) {
        st.execute(
            "SELECT pg_advisory_xact_lock_shared(hashtext('cash_window:" + BUSINESS_ID + "'))");
      }

      CountDownLatch closeDone = new CountDownLatch(1);
      AtomicReference<RegisterSessionResponse> closeResult = new AtomicReference<>();
      AtomicReference<Throwable> closeError = new AtomicReference<>();
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        pool.submit(
            () -> {
              try {
                RegisterSessionResponse response =
                    TenantContext.callAs(
                        TENANT,
                        ACTOR,
                        () ->
                            registerSessionService.close(
                                sessionId, new CloseSessionRequest(100_000L), "lock-close-key"));
                closeResult.set(response);
              } catch (Throwable t) {
                closeError.set(t);
              } finally {
                closeDone.countDown();
              }
            });

        // STILL BLOCKED: a short, bounded wait must NOT observe completion while the manual
        // SHARED holder is still active — this is the load-bearing assertion that close()'s
        // EXCLUSIVE acquisition actually contends the SAME key against a SHARED holder, not a
        // sleep-based flakiness risk (we assert non-completion, never rely on a sleep to "make"
        // it complete).
        boolean finishedWhileLocked = closeDone.await(500, TimeUnit.MILLISECONDS);
        assertThat(finishedWhileLocked)
            .as("close() must block while a SHARED CashWindowLock holder is active")
            .isFalse();

        // Release the manually-held lock — close() should now proceed.
        lockHolder.rollback();

        // UNBLOCKS: a generous bounded wait for completion (never a sleep-as-mechanism; this
        // await returns as soon as the close call actually finishes).
        boolean finishedAfterRelease = closeDone.await(10, TimeUnit.SECONDS);
        assertThat(finishedAfterRelease)
            .as("close() must complete promptly once the CashWindowLock is released")
            .isTrue();
      } finally {
        pool.shutdownNow();
      }

      assertThat(closeError.get()).as("close() must not fail once unblocked").isNull();
      assertThat(closeResult.get()).isNotNull();
      assertThat(closeResult.get().status()).isEqualTo("CLOSED");
    }
  }

  /**
   * The other half of the readers-writer contract: two SHARED holders (two concurrent
   * sale/refund-committing transactions on the SAME business) must NOT block each other — only
   * EXCLUSIVE (close) contends against SHARED. This is exactly what fixes the regression a plain
   * mutex caused (it broke {@code LoyaltyGiftCardRedemptionConcurrencyTest}, which requires two
   * genuinely concurrent checkouts racing the same cached balance).
   */
  @Test
  void twoSharedCashWindowLockHoldersDoNotBlockEachOther() throws Exception {
    String key = "cash_window:" + BUSINESS_ID;
    try (Connection first =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Connection second =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      first.setAutoCommit(false);
      second.setAutoCommit(false);

      // Both acquire the SHARED lock on the SAME key. Neither may block the other — proven by
      // both statements returning within a short bounded time, run sequentially (if the second
      // blocked on the first, this call itself would hang past the test timeout).
      try (Statement st = first.createStatement()) {
        st.execute("SELECT pg_advisory_xact_lock_shared(hashtext('" + key + "'))");
      }
      try (Statement st = second.createStatement()) {
        st.execute("SELECT pg_advisory_xact_lock_shared(hashtext('" + key + "'))");
      }

      // Both connections successfully hold the SHARED lock simultaneously — release cleanly.
      first.rollback();
      second.rollback();
    }
  }
}
