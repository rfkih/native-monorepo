package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * C1 (code review, CRITICAL) concurrency proof: a cash {@code payBill} racing a gateway {@code
 * initiatePendingPayment} on the SAME bill must never double-settle a line — exactly one of the two
 * wins, the loser's ENTIRE transaction rolls back (no orphaned PENDING payment, no phantom sale),
 * and no line ever ends up both cash-paid and gateway-reserved.
 *
 * <p>Mirrors {@code LoyaltyGiftCardRedemptionConcurrencyTest}'s two-thread {@link CyclicBarrier}
 * harness: both threads {@code barrier.await()} immediately inside their {@link TenantContext}
 * scope, so both {@code payBill}/{@code initiatePendingPayment} calls start at (as close as the JVM
 * can get to) the SAME instant. The outcome is deterministic REGARDLESS of exactly how the two
 * transactions interleave from there — whichever of the two guarded, mutually-exclusive UPDATEs
 * ({@code BillLineRepository#markLinesPaidForCash} vs {@code #reserveUnpaidLines}) commits FIRST
 * against the target {@code bill_line} row(s) wins outright; Postgres row-level locking serializes
 * the second one behind it, and — under READ COMMITTED — that second UPDATE re-evaluates its
 * guarded {@code WHERE} clause against the now-committed loser state and matches ZERO rows, so the
 * caller's row-count check fires and the whole losing transaction rolls back. This is exactly the
 * "read-before/write-after" race the C1 fix closes: {@code payBill}'s initial unpaid-line SELECT (a
 * plain read, no lock) is NOT what protects the cash path — the LATE guarded UPDATE is.
 */
@SpringBootTest
class BillGatewayConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "e3000001-e300-e300-e300-e30000000001";
  private static final String ACTOR = "cashier-race@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("e3000001-e300-e300-e300-e30000000002");

  @Autowired private MenuService menuService;
  @Autowired private BillService billService;

  /**
   * Repeated several times: which side wins the DB-level race can vary run to run depending on
   * thread scheduling (both start at the barrier, but {@code payBill}'s path to its guarded write
   * does more work — stock deduction, ingredient depletion, the sale insert + outbox write — than
   * {@code initiatePendingPayment}'s path to ITS guarded write, so which one's UPDATE actually
   * reaches Postgres first is not fixed). The INVARIANT under test — exactly one winner, the loser
   * cleanly rolled back, no line ever both paid and reserved — must hold on EVERY interleaving, so
   * repeating raises confidence the fix is correct regardless of which side happens to win.
   */
  @RepeatedTest(5)
  void concurrentCashPayBillAndGatewayPayPendingNeverDoubleSettleTheSameLine() throws Exception {
    UUID itemId = seedItem(20_000L);
    UUID billId = openBillWithLine(itemId, 1); // 20,000, one line

    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<BillResponse> cashAttempt =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return billService.payBill(billId, new PayBillRequest());
                });
    Callable<PaymentResponse> gatewayAttempt =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return billService.initiatePendingPayment(
                      billId, new PayBillRequest(new PaymentRequest(TenderType.QRIS, null), null));
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    boolean cashWon = false;
    boolean cashLost = false;
    boolean gatewayWon = false;
    boolean gatewayLost = false;
    UUID mintedPaymentId = null;
    try {
      Future<BillResponse> cashFuture = pool.submit(cashAttempt);
      Future<PaymentResponse> gatewayFuture = pool.submit(gatewayAttempt);

      try {
        BillResponse cashResult = cashFuture.get();
        assertThat(cashResult.status()).isEqualTo("PAID");
        cashWon = true;
      } catch (ExecutionException ex) {
        assertThat(ex.getCause())
            .as("the cash loser must fail with a RECOGNIZED conflict, not some other error")
            .isInstanceOfAny(
                IllegalArgumentException.class, // "Nothing to pay" (gateway's reserve committed
                // before cash's own initial unpaid-line read)
                id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException.class);
        cashLost = true;
      }

      try {
        PaymentResponse gatewayResult = gatewayFuture.get();
        assertThat(gatewayResult.status()).isEqualTo("PENDING");
        mintedPaymentId = gatewayResult.paymentId();
        gatewayWon = true;
      } catch (ExecutionException ex) {
        assertThat(ex.getCause())
            .as("the gateway loser must fail with the reservation-conflict exception")
            .isInstanceOf(
                id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException.class);
        gatewayLost = true;
      }
    } finally {
      pool.shutdownNow();
    }

    // Exactly one side won — never both, never neither.
    assertThat(cashWon ^ gatewayWon).as("exactly one of cash/gateway must win").isTrue();
    assertThat(cashWon == cashLost).as("cash has exactly one outcome").isFalse();
    assertThat(gatewayWon == gatewayLost).as("gateway has exactly one outcome").isFalse();

    // The core C1 invariant: no line is ever both cash-paid AND gateway-reserved, and the DB state
    // matches whichever side actually won — never a mix, never orphaned state from the loser.
    if (cashWon) {
      assertThat(billStatusAsAdmin(billId)).isEqualTo("PAID");
      assertThat(paidLineCountAsAdmin(billId)).isEqualTo(1L);
      assertThat(reservedLineCountAsAdmin(billId)).isZero();
      // The gateway loser's ENTIRE transaction rolled back — no orphaned PENDING payment at all.
      assertThat(paymentCountForBillAsAdmin(billId)).isZero();
      assertThat(saleCountAsAdmin()).isEqualTo(1L);
    } else {
      assertThat(billStatusAsAdmin(billId)).isEqualTo("OPEN");
      assertThat(paidLineCountAsAdmin(billId)).isZero();
      assertThat(reservedLineCountAsAdmin(billId)).isEqualTo(1L);
      // The cash loser's ENTIRE transaction rolled back — no sale, no stock/promotion side effects.
      assertThat(saleCountAsAdmin()).isZero();
      assertThat(mintedPaymentId).isNotNull();
      assertThat(paymentCountForBillAsAdmin(billId)).isEqualTo(1L);
      assertThat(paymentStatusAsAdmin(mintedPaymentId)).isEqualTo("PENDING");
    }
  }

  // -----------------------------------------------------------------------
  // Setup helpers
  // -----------------------------------------------------------------------

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS, "Race Item", "MAIN", priceMinor, "IDR"))
                .id());
  }

  private UUID openBillWithLine(UUID itemId, int qty) throws Exception {
    BillResponse opened =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "Racer")));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                opened.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, qty)))));
    return opened.id();
  }

  // -----------------------------------------------------------------------
  // Admin (BYPASSRLS) helpers
  // -----------------------------------------------------------------------

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private String billStatusAsAdmin(UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT status FROM bill WHERE id = ?")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("bill row must exist").isTrue();
        return rs.getString(1);
      }
    }
  }

  private long paidLineCountAsAdmin(UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM bill_line WHERE bill_id = ? AND paid = true")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long reservedLineCountAsAdmin(UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM bill_line WHERE bill_id = ? AND pending_payment_id IS NOT"
                    + " NULL")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long paymentCountForBillAsAdmin(UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM payment WHERE bill_id = ?")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String paymentStatusAsAdmin(UUID paymentId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement("SELECT status FROM payment WHERE id = ?")) {
      ps.setObject(1, paymentId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("payment row must exist").isTrue();
        return rs.getString(1);
      }
    }
  }

  private long saleCountAsAdmin() throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM sale WHERE business_id = ?")) {
      ps.setObject(1, BUSINESS);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
