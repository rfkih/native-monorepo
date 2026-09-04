package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.domain.BillHasPaidLinesException;
import id.co.nativeapp.restaurant.bill.domain.BillLineReservedException;
import id.co.nativeapp.restaurant.bill.domain.BillNotOpenException;
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
 * 2026-08-31 audit C1/H1 — cancel must serialize against the line-mutating write paths.
 *
 * <p>{@code Bill.cancel()}'s paid/reserved-line guard is a READ of child rows, and a PARTIAL
 * split-pay ({@code markLinesPaidForCash}) or a gateway reservation ({@code reserveUnpaidLines})
 * mutates {@code bill_line} via native UPDATEs that never dirty the parent {@code bill} row — so
 * optimistic {@code @Version} alone cannot arbitrate cancel vs. those writers, and before the fix a
 * cancel racing a partial split-pay could commit a CANCELLED bill with a recorded sale stranded on
 * it (or a live PSP reservation, the H1 variant). The fix makes every such path load the bill
 * {@code FOR UPDATE} ({@code BillRepository#findWithLockById}); these races then serialize on the
 * bill row and the loser fails with the CORRECT domain exception.
 *
 * <p>Mirrors {@link BillGatewayConcurrencyTest}'s barrier/executor harness: the invariant must hold
 * on EVERY interleaving, so each race is repeated.
 */
@SpringBootTest
class BillCancelRaceTest extends PostgresRlsTestBase {

  private static final String TENANT = "88888888-8888-8888-8888-888888888888";
  private static final String ACTOR = "cancel-race@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("88888888-8888-8888-8888-888888880001");

  @Autowired private MenuService menuService;
  @Autowired private BillService billService;

  // -----------------------------------------------------------------------
  // C1 — cancel vs PARTIAL split-pay: never a CANCELLED bill with a paid line
  // -----------------------------------------------------------------------

  @RepeatedTest(5)
  void cancelRacingAPartialSplitPayNeverStrandsARecordedSale() throws Exception {
    UUID itemA = seedItem("Sate Kambing");
    UUID itemB = seedItem("Es Jeruk");
    UUID billId = openBillWithLines(itemA, itemB);
    UUID lineAId =
        TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(billId)).lines().get(0).id();

    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<BillResponse> partialPay =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return billService.payBill(
                      billId, new PayBillRequest(null, null, List.of(lineAId), null, null));
                });
    Callable<Void> cancel =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  billService.cancelBill(billId);
                  return null;
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    boolean payWon = false;
    boolean cancelWon = false;
    try {
      Future<BillResponse> payFuture = pool.submit(partialPay);
      Future<Void> cancelFuture = pool.submit(cancel);

      try {
        payFuture.get();
        payWon = true;
      } catch (ExecutionException ex) {
        assertThat(ex.getCause())
            .as("the pay loser must see the committed CANCELLED status, not a raw error")
            .isInstanceOf(BillNotOpenException.class);
      }
      try {
        cancelFuture.get();
        cancelWon = true;
      } catch (ExecutionException ex) {
        assertThat(ex.getCause())
            .as("the cancel loser must be refused by the paid-lines guard")
            .isInstanceOf(BillHasPaidLinesException.class);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(payWon ^ cancelWon).as("exactly one of pay/cancel must win").isTrue();

    // THE invariant: a CANCELLED bill may never carry a paid line / recorded sale.
    if (cancelWon) {
      assertThat(billStatusAsAdmin(billId)).isEqualTo("CANCELLED");
      assertThat(paidLineCountAsAdmin(billId)).isZero();
      assertThat(saleCountForBillAsAdmin(billId)).isZero();
    } else {
      assertThat(billStatusAsAdmin(billId)).isEqualTo("OPEN"); // one of two lines paid
      assertThat(paidLineCountAsAdmin(billId)).isEqualTo(1L);
      assertThat(saleCountForBillAsAdmin(billId)).isEqualTo(1L);
    }
  }

  // -----------------------------------------------------------------------
  // H1 — cancel vs gateway reservation: never a CANCELLED bill with reserved lines
  // -----------------------------------------------------------------------

  @RepeatedTest(5)
  void cancelRacingAGatewayReservationNeverStrandsALivePayment() throws Exception {
    UUID itemId = seedItem("Nasi Campur");
    UUID billId = openBillWithLines(itemId);

    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<Object> reserve =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  return billService.initiatePendingPayment(
                      billId, new PayBillRequest(new PaymentRequest(TenderType.QRIS, null), null));
                });
    Callable<Void> cancel =
        () ->
            TenantContext.callAs(
                TENANT,
                ACTOR,
                () -> {
                  barrier.await();
                  billService.cancelBill(billId);
                  return null;
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    boolean reserveWon = false;
    boolean cancelWon = false;
    try {
      Future<Object> reserveFuture = pool.submit(reserve);
      Future<Void> cancelFuture = pool.submit(cancel);

      try {
        reserveFuture.get();
        reserveWon = true;
      } catch (ExecutionException ex) {
        assertThat(ex.getCause())
            .as("the reserve loser must see the committed CANCELLED status")
            .isInstanceOf(BillNotOpenException.class);
      }
      try {
        cancelFuture.get();
        cancelWon = true;
      } catch (ExecutionException ex) {
        assertThat(ex.getCause())
            .as("the cancel loser must be refused by the reserved-lines guard")
            .isInstanceOf(BillLineReservedException.class);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(reserveWon ^ cancelWon).as("exactly one of reserve/cancel must win").isTrue();

    if (cancelWon) {
      assertThat(billStatusAsAdmin(billId)).isEqualTo("CANCELLED");
      assertThat(reservedLineCountAsAdmin(billId)).isZero();
    } else {
      assertThat(billStatusAsAdmin(billId)).isEqualTo("OPEN");
      assertThat(reservedLineCountAsAdmin(billId)).isEqualTo(1L);
    }
  }

  // -----------------------------------------------------------------------
  // Setup helpers
  // -----------------------------------------------------------------------

  private UUID seedItem(String name) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS, name, "MAIN", 25_000L, "IDR"))
                .id());
  }

  private UUID openBillWithLines(UUID... itemIds) throws Exception {
    BillResponse opened =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.open(new OpenBillRequest(BUSINESS, null, "Racer")));
    List<OrderLineRequest> lines =
        java.util.Arrays.stream(itemIds).map(id -> new OrderLineRequest(id, 1)).toList();
    TenantContext.callAs(
        TENANT, ACTOR, () -> billService.appendLines(opened.id(), new AppendLinesRequest(lines)));
    return opened.id();
  }

  // -----------------------------------------------------------------------
  // Admin (BYPASSRLS) helpers — mirror BillGatewayConcurrencyTest
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
    return countAsAdmin("SELECT count(*) FROM bill_line WHERE bill_id = ? AND paid = true", billId);
  }

  private long reservedLineCountAsAdmin(UUID billId) throws Exception {
    return countAsAdmin(
        "SELECT count(*) FROM bill_line WHERE bill_id = ? AND pending_payment_id IS NOT NULL",
        billId);
  }

  /** Sales are scoped PER BILL via bill_line.paid_sale_id (repeated runs share the database). */
  private long saleCountForBillAsAdmin(UUID billId) throws Exception {
    return countAsAdmin(
        "SELECT count(DISTINCT paid_sale_id) FROM bill_line WHERE bill_id = ? AND paid_sale_id IS"
            + " NOT NULL",
        billId);
  }

  private long countAsAdmin(String sql, UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
