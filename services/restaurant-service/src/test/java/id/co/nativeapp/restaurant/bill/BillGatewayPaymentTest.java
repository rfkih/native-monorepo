package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException;
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
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * V38 — dynamic QRIS/CARD gateway support for BILLS (full-bill only). Mirrors the ORDER
 * digital-tender two-step ({@code OrderWriter.checkout} + {@code PaymentCaptureWriter}), but for
 * the {@code bill} (guest tab) aggregate: {@link BillService#initiatePendingPayment} mints a
 * PENDING {@code payment} row and RESERVES every unpaid line; {@code BillPaymentCaptureWriter}
 * (dispatched via {@link PaymentCaptureService#capture}) records the check's Sale, flips the bill
 * to PAID, and clears the reservation.
 *
 * <p>The {@code PaymentChargeSucceeded}-driven (Kafka) capture path — including the "still routes
 * an order payment to order-capture" regression — is covered separately in {@link
 * id.co.nativeapp.restaurant.payment.BillPaymentChargeSucceededTest} (needs a real broker).
 */
@SpringBootTest
class BillGatewayPaymentTest extends PostgresRlsTestBase {

  private static final String TENANT = "e1000001-e100-e100-e100-e10000000001";
  private static final String ACTOR = "cashier-gw@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("e1000001-e100-e100-e100-e10000000002");

  @Autowired private MenuService menuService;
  @Autowired private BillService billService;
  @Autowired private PaymentCaptureService captureService;

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
                    new CreateMenuItemRequest(BUSINESS, "Nasi Ayam", "MAIN", priceMinor, "IDR"))
                .id());
  }

  /** Opens a bill and appends ONE round with {@code qty} of {@code itemId}. */
  private UUID openBillWithLine(UUID itemId, int qty) throws Exception {
    BillResponse opened =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> billService.open(new OpenBillRequest(BUSINESS, null, "Gateway Guest")));
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            billService.appendLines(
                opened.id(), new AppendLinesRequest(List.of(new OrderLineRequest(itemId, qty)))));
    return opened.id();
  }

  private PayBillRequest qrisRequest() {
    return new PayBillRequest(new PaymentRequest(TenderType.QRIS, null), null);
  }

  // -----------------------------------------------------------------------
  // 1. pay-pending mints a PENDING payment, reserves lines, records no sale
  // -----------------------------------------------------------------------

  @Test
  void payPendingCreatesAPendingPaymentReservesLinesAndRecordsNoSale() throws Exception {
    UUID itemId = seedItem(20_000L);
    UUID billId = openBillWithLine(itemId, 2); // 40,000

    long saleCountBefore = saleCountAsAdmin();

    PaymentResponse pending =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, qrisRequest()));

    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.tenderType()).isEqualTo("QRIS");
    assertThat(pending.billId()).isEqualTo(billId);
    assertThat(pending.orderId()).isNull();
    assertThat(pending.amountMinor()).isEqualTo(40_000L);
    assertThat(pending.currency()).isEqualTo("IDR");
    assertThat(pending.providerPending()).isTrue();
    assertThat(pending.saleId()).isNull();

    // No sale recorded yet.
    assertThat(saleCountAsAdmin()).isEqualTo(saleCountBefore);

    // The bill's line is RESERVED against the PENDING payment; the bill itself stays OPEN.
    assertThat(billStatusAsAdmin(billId)).isEqualTo("OPEN");
    assertThat(pendingPaymentIdsOnBillAsAdmin(billId))
        .containsExactly(pending.paymentId())
        .hasSize(1);
  }

  @Test
  void payPendingRejectsANonDigitalTenderType() throws Exception {
    UUID itemId = seedItem(10_000L);
    UUID billId = openBillWithLine(itemId, 1);

    PayBillRequest cashRequest =
        new PayBillRequest(new PaymentRequest(TenderType.CASH, null), null);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, cashRequest)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digital");
  }

  // -----------------------------------------------------------------------
  // 2. capture records the check sale once, closes the bill, clears the
  //    reservation, and is idempotent on redelivery
  // -----------------------------------------------------------------------

  @Test
  void captureRecordsTheSaleOnceClosesTheBillClearsTheReservationAndIsIdempotent()
      throws Exception {
    UUID itemId = seedItem(15_000L);
    UUID billId = openBillWithLine(itemId, 3); // 45,000

    PaymentResponse pending =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, qrisRequest()));

    long saleCountBefore = saleCountAsAdmin();

    PaymentResponse captured =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(pending.paymentId()));

    assertThat(captured.status()).isEqualTo("CAPTURED");
    assertThat(captured.saleId()).isNotNull();
    assertThat(captured.amountMinor()).isEqualTo(45_000L);

    // Exactly one sale recorded.
    assertThat(saleCountAsAdmin()).isEqualTo(saleCountBefore + 1);

    // Bill flips to PAID with the captured sale id.
    BillResponse billAfter = TenantContext.callAs(TENANT, ACTOR, () -> billService.getById(billId));
    assertThat(billAfter.status()).isEqualTo("PAID");
    assertThat(billAfter.saleId()).isEqualTo(captured.saleId());

    // Reservation cleared, line marked paid.
    assertThat(pendingPaymentIdsOnBillAsAdmin(billId)).isEmpty();
    assertThat(rowCountAsAdmin("bill_line WHERE bill_id = '" + billId + "' AND paid = false"))
        .isZero();

    // Second capture (redelivery) — idempotent no-op: same state, no second sale.
    PaymentResponse recaptured =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(pending.paymentId()));
    assertThat(recaptured.status()).isEqualTo("CAPTURED");
    assertThat(recaptured.saleId()).isEqualTo(captured.saleId());
    assertThat(saleCountAsAdmin()).isEqualTo(saleCountBefore + 1); // still exactly one
  }

  // -----------------------------------------------------------------------
  // 3. abandon releases the reservation — lines payable again by cash
  // -----------------------------------------------------------------------

  @Test
  void abandonReleasesTheReservationSoLinesArePayableAgainByCash() throws Exception {
    UUID itemId = seedItem(12_000L);
    UUID billId = openBillWithLine(itemId, 1);

    PaymentResponse pending =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, qrisRequest()));
    assertThat(pendingPaymentIdsOnBillAsAdmin(billId)).hasSize(1);

    PaymentResponse abandoned =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.abandon(pending.paymentId()));
    assertThat(abandoned.status()).isEqualTo("ABANDONED");

    // Reservation released; bill line payable again.
    assertThat(pendingPaymentIdsOnBillAsAdmin(billId)).isEmpty();

    BillResponse paidByCash =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.payBill(billId, new PayBillRequest()));
    assertThat(paidByCash.status()).isEqualTo("PAID");
    assertThat(paidByCash.breakdown().grandTotalMinor()).isEqualTo(12_000L);
  }

  // -----------------------------------------------------------------------
  // 4. a concurrent cash payBill of reserved lines finds nothing to pay —
  //    the reservation holds
  // -----------------------------------------------------------------------

  @Test
  void cashPayBillOfReservedLinesFindsNothingToPay() throws Exception {
    UUID itemId = seedItem(9_000L);
    UUID billId = openBillWithLine(itemId, 1);

    TenantContext.callAs(
        TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, qrisRequest()));

    // A concurrent cash pay attempt on the SAME (now fully-reserved) bill finds nothing payable.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> billService.payBill(billId, new PayBillRequest())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nothing to pay");

    // The bill stays OPEN — the reservation, not a completed payment, is what blocked it.
    assertThat(billStatusAsAdmin(billId)).isEqualTo("OPEN");
  }

  // -----------------------------------------------------------------------
  // 5. amount-drift on capture parks — never captures a different amount
  // -----------------------------------------------------------------------

  @Test
  void captureParksOnAnAmountDriftInsteadOfCapturingTheWrongAmount() throws Exception {
    UUID itemId = seedItem(25_000L);
    UUID billId = openBillWithLine(itemId, 1);

    PaymentResponse pending =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, qrisRequest()));

    // Simulate drift: corrupt the PENDING payment's own stored amount (a value that could never
    // legitimately arise from BillWriter's own computation) directly at the DB, bypassing the
    // application layer — mirrors PaymentChargeSucceededConsumeAcceptanceTest's amount-mismatch
    // technique (there the EVENT amount is wrong; here the source of truth to recompute AGAINST is
    // wrong instead, since a bill capture recomputes from its own persisted lines/discount, not
    // from an externally supplied amount).
    corruptPaymentAmountAsAdmin(pending.paymentId(), 999_999L);

    long saleCountBefore = saleCountAsAdmin();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> captureService.capture(pending.paymentId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch");

    // No sale recorded, payment stays PENDING (parked, not captured).
    assertThat(saleCountAsAdmin()).isEqualTo(saleCountBefore);
    assertThat(paymentStatusAsAdmin(pending.paymentId())).isEqualTo("PENDING");
  }

  // -----------------------------------------------------------------------
  // 6. self-heal: a second pay-pending abandons the stale first attempt
  // -----------------------------------------------------------------------

  @Test
  void aSecondPayPendingSelfHealsByAbandoningTheFirstStalePendingPayment() throws Exception {
    UUID itemId = seedItem(30_000L);
    UUID billId = openBillWithLine(itemId, 1);

    PaymentResponse first =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, qrisRequest()));
    PaymentResponse second =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billService.initiatePendingPayment(billId, qrisRequest()));

    assertThat(second.paymentId()).isNotEqualTo(first.paymentId());
    assertThat(paymentStatusAsAdmin(first.paymentId())).isEqualTo("ABANDONED");
    assertThat(paymentStatusAsAdmin(second.paymentId())).isEqualTo("PENDING");

    // Exactly the SECOND payment holds the reservation now.
    assertThat(pendingPaymentIdsOnBillAsAdmin(billId)).containsExactly(second.paymentId());

    // The second one captures cleanly.
    PaymentResponse captured =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(second.paymentId()));
    assertThat(captured.status()).isEqualTo("CAPTURED");
  }

  // -----------------------------------------------------------------------
  // 7. reservation conflict maps to a dedicated, 409-mapped exception
  // -----------------------------------------------------------------------

  @Test
  void reservationConflictExceptionCarriesTheExpectedAndActualCounts() {
    UUID billId = UUID.randomUUID();
    BillLineReservationConflictException ex =
        new BillLineReservationConflictException(billId, 2, 1);
    assertThat(ex.getBillId()).isEqualTo(billId);
    assertThat(ex.getExpectedUnpaidLines()).isEqualTo(2);
    assertThat(ex.getReservedLines()).isEqualTo(1);
    // C1 fix (code review): this exception is now shared by BOTH the reservation UPDATE and the
    // guarded mark-paid UPDATEs, so the message is deliberately generic ("line-claim conflict"),
    // and now extends IllegalStateException (so a capture-path occurrence auto-parks).
    assertThat(ex.getMessage()).contains("line-claim conflict");
    assertThat(ex).isInstanceOf(IllegalStateException.class);
  }

  // -----------------------------------------------------------------------
  // Admin (BYPASSRLS) helpers
  // -----------------------------------------------------------------------

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private long saleCountAsAdmin() throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM sale")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long rowCountAsAdmin(String tableClause) throws Exception {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + tableClause)) {
      rs.next();
      return rs.getLong(1);
    }
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

  private List<UUID> pendingPaymentIdsOnBillAsAdmin(UUID billId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT pending_payment_id FROM bill_line WHERE bill_id = ? AND"
                    + " pending_payment_id IS NOT NULL")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        List<UUID> result = new java.util.ArrayList<>();
        while (rs.next()) {
          result.add((UUID) rs.getObject(1));
        }
        return result;
      }
    }
  }

  private void corruptPaymentAmountAsAdmin(UUID paymentId, long wrongAmountMinor) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("UPDATE payment SET amount_minor = ? WHERE id = ?")) {
      ps.setLong(1, wrongAmountMinor);
      ps.setObject(2, paymentId);
      ps.executeUpdate();
    }
  }
}
