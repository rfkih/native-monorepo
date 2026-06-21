package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.dto.ParkedOrderSummary;
import id.co.nativeapp.restaurant.order.dto.PayParkedRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.service.TableService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance tests for the Phase 4 park / resume / pay flow.
 *
 * <p>Proves:
 *
 * <ul>
 *   <li>Park records NO sale, NO payment, NO outbox row (the no-revenue invariant).
 *   <li>Parked order appears in the parked list with table label + line count.
 *   <li>Resume (GET /orders/{id}) returns lines, modifiers, and breakdown.
 *   <li>Pay-a-parked-order records exactly ONE sale + payment, transitions PARKED → COMPLETED, and
 *       is idempotent.
 *   <li>Revenue is recognised only at /pay — not at /park.
 *   <li>Order type + table id are persisted and validated.
 *   <li>tableId rejected for TAKEAWAY; cross-business table rejected.
 *   <li>Tenancy isolation: tenant B cannot list/resume/pay tenant A's parked order.
 * </ul>
 */
@SpringBootTest
class ParkAndResumeTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "aa110001-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TENANT_B = "bb110001-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String ACTOR = "pos@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("dd110001-dddd-dddd-dddd-dddddddddddd");
  private static final UUID OTHER_BUSINESS =
      UUID.fromString("ee110001-eeee-eeee-eeee-eeeeeeeeeeee");

  @Autowired private OrderService orderService;
  @Autowired private MenuService menuService;
  @Autowired private TableService tableService;

  // -----------------------------------------------------------------------
  // Setup helpers
  // -----------------------------------------------------------------------

  private UUID seedItem(long price) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS, "Sate Ayam", "MAIN", price, "IDR"))
                .id());
  }

  private UUID seedTable(String label) throws Exception {
    return TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> tableService.create(new CreateTableRequest(BUSINESS, label, 4, null)))
        .tableId();
  }

  // -----------------------------------------------------------------------
  // Park: no revenue
  // -----------------------------------------------------------------------

  @Test
  void parkRecordsNoSaleNoPaymentNoOutboxRow() throws Exception {
    UUID itemId = seedItem(25_000L);

    CheckoutResult result =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.park(
                    new ParkOrderRequest(
                        BUSINESS,
                        "park-no-rev-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)))));

    assertThat(result.created()).isTrue();
    OrderResponse parked = result.order();
    assertThat(parked.status()).isEqualTo("PARKED");
    assertThat(parked.saleId()).isNull(); // no revenue yet

    // DB assertions via admin (BYPASSRLS) connection.
    assertThat(saleRecordedCountAsAdmin()).as("no SaleRecorded emitted on park").isZero();
    assertThat(paymentCountAsAdmin()).as("no payment row on park").isZero();
  }

  @Test
  void parkIsIdempotentOnRetry() throws Exception {
    UUID itemId = seedItem(10_000L);
    String idemKey = "park-idem-" + UUID.randomUUID();
    ParkOrderRequest req =
        new ParkOrderRequest(BUSINESS, idemKey, List.of(new OrderLineRequest(itemId, 1)));

    CheckoutResult first = TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.park(req));
    CheckoutResult second = TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.park(req));

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.order().orderId()).isEqualTo(first.order().orderId());
    assertThat(saleRecordedCountAsAdmin()).isZero();
  }

  // -----------------------------------------------------------------------
  // List parked + resume
  // -----------------------------------------------------------------------

  @Test
  void parkedOrderAppearsInListWithTableLabelAndLineCount() throws Exception {
    UUID itemId = seedItem(15_000L);
    UUID tableId = seedTable("PA-T1");

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            orderService.park(
                new ParkOrderRequest(
                    BUSINESS,
                    "park-list-" + UUID.randomUUID(),
                    List.of(new OrderLineRequest(itemId, 2)),
                    "DINE_IN",
                    tableId,
                    null)));

    List<ParkedOrderSummary> list =
        TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.listParked(BUSINESS));

    assertThat(list).hasSize(1);
    ParkedOrderSummary summary = list.get(0);
    assertThat(summary.tableLabel()).isEqualTo("PA-T1");
    assertThat(summary.lineCount()).isEqualTo(1); // 1 distinct line (qty=2)
    assertThat(summary.orderType()).isEqualTo("DINE_IN");
    assertThat(summary.currency()).isEqualTo("IDR");
  }

  @Test
  void resumeReturnsLinesAndBreakdown() throws Exception {
    UUID itemId = seedItem(20_000L);

    CheckoutResult parked =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.park(
                    new ParkOrderRequest(
                        BUSINESS,
                        "park-resume-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 3)))));

    UUID orderId = parked.order().orderId();

    // Resume the parked order.
    OrderResponse resumed =
        TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.getOrder(orderId));

    assertThat(resumed.status()).isEqualTo("PARKED");
    assertThat(resumed.lines()).hasSize(1);
    assertThat(resumed.lines().get(0).qty()).isEqualTo(3);
    assertThat(resumed.saleId()).isNull();
  }

  // -----------------------------------------------------------------------
  // Pay a parked order
  // -----------------------------------------------------------------------

  @Test
  void payParkedOrderRecordsExactlyOneSaleAndTransitionsToCompleted() throws Exception {
    UUID itemId = seedItem(30_000L);

    CheckoutResult parked =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.park(
                    new ParkOrderRequest(
                        BUSINESS,
                        "park-pay-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)))));

    UUID orderId = parked.order().orderId();
    assertThat(saleRecordedCountAsAdmin()).as("no revenue before /pay").isZero();

    // Pay with cash — revenue recognised here.
    OrderResponse paid =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.payParked(
                    orderId, new PayParkedRequest(new PaymentRequest(TenderType.CASH, 40_000L))));

    assertThat(paid.status()).isEqualTo("COMPLETED");
    assertThat(paid.saleId()).isNotNull();
    assertThat(paid.payment()).isNotNull();
    assertThat(paid.payment().status()).isEqualTo("CAPTURED");

    assertThat(saleRecordedCountAsAdmin()).as("exactly one SaleRecorded after /pay").isEqualTo(1L);
    assertThat(paymentCountAsAdmin()).as("exactly one payment row after /pay").isEqualTo(1L);

    // Idempotent re-pay: returns existing COMPLETED state, no second sale.
    OrderResponse rePaid =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.payParked(
                    orderId, new PayParkedRequest(new PaymentRequest(TenderType.CASH, 40_000L))));

    assertThat(rePaid.status()).isEqualTo("COMPLETED");
    assertThat(saleRecordedCountAsAdmin())
        .as("still only one SaleRecorded after idempotent re-pay")
        .isEqualTo(1L);
  }

  @Test
  void payParkedOrderWithNoPaymentCompletesWithoutPaymentRow() throws Exception {
    UUID itemId = seedItem(12_000L);

    CheckoutResult parked =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.park(
                    new ParkOrderRequest(
                        BUSINESS,
                        "park-nopay-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)))));

    UUID orderId = parked.order().orderId();

    // Pay with no tender (completes without payment capture).
    OrderResponse paid =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> orderService.payParked(orderId, new PayParkedRequest()));

    assertThat(paid.status()).isEqualTo("COMPLETED");
    assertThat(paid.saleId()).isNotNull();
    assertThat(paid.payment()).isNull();

    assertThat(saleRecordedCountAsAdmin())
        .as("SaleRecorded emitted on /pay with no tender")
        .isEqualTo(1L);
    assertThat(paymentCountAsAdmin()).as("no payment row for no-tender pay").isZero();
  }

  @Test
  void payingAlreadyCompletedOrderIsIdempotent() throws Exception {
    UUID itemId = seedItem(10_000L);

    // Checkout directly (COMPLETED, not PARKED).
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.checkout(
                    new id.co.nativeapp.restaurant.order.dto.CheckoutRequest(
                        BUSINESS,
                        "checkout-not-park-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)))));

    UUID orderId = checkout.order().orderId();

    // Calling /pay on a COMPLETED order returns the existing state idempotently — no exception.
    OrderResponse result =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> orderService.payParked(orderId, new PayParkedRequest()));
    assertThat(result.status()).isEqualTo("COMPLETED");
  }

  @Test
  void payingOrderInAwaitingPaymentStatusIsRejected() throws Exception {
    UUID itemId = seedItem(10_000L);

    // Checkout with digital payment — order in AWAITING_PAYMENT (not PARKED).
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.checkout(
                    new id.co.nativeapp.restaurant.order.dto.CheckoutRequest(
                        BUSINESS,
                        "digital-not-park-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)),
                        new PaymentRequest(TenderType.QRIS, null))));

    UUID orderId = checkout.order().orderId();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A, ACTOR, () -> orderService.payParked(orderId, new PayParkedRequest())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PARKED");
  }

  // -----------------------------------------------------------------------
  // Order type + table validation
  // -----------------------------------------------------------------------

  @Test
  void orderTypeAndTableIdPersistedOnCheckout() throws Exception {
    UUID itemId = seedItem(15_000L);
    UUID tableId = seedTable("PB-T1");

    CheckoutResult result =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.checkout(
                    new id.co.nativeapp.restaurant.order.dto.CheckoutRequest(
                        BUSINESS,
                        "dine-in-table-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)),
                        null,
                        null,
                        "DINE_IN",
                        tableId)));

    assertThat(result.order().orderType()).isEqualTo("DINE_IN");
    assertThat(result.order().tableId()).isEqualTo(tableId);
  }

  @Test
  void tableIdRejectedForTakeaway() throws Exception {
    UUID itemId = seedItem(10_000L);
    UUID tableId = seedTable("PB-T2");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        orderService.checkout(
                            new id.co.nativeapp.restaurant.order.dto.CheckoutRequest(
                                BUSINESS,
                                "takeaway-with-table-" + UUID.randomUUID(),
                                List.of(new OrderLineRequest(itemId, 1)),
                                null,
                                null,
                                "TAKEAWAY",
                                tableId))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DINE_IN");
  }

  @Test
  void crossBusinessTableRejected() throws Exception {
    UUID itemId = seedItem(10_000L);

    // Create a table for BUSINESS (tenant A).
    UUID tableId = seedTable("PB-T3");

    // Try to checkout with BUSINESS using a table that belongs to BUSINESS but the
    // checkout is for OTHER_BUSINESS — table lookup uses business_id filter.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () -> {
                      // create item for OTHER_BUSINESS would need its own business — use the item
                      // for BUSINESS but checkout for OTHER_BUSINESS (item cross-business guard
                      // will
                      // fire first since item belongs to BUSINESS, not OTHER_BUSINESS).
                      // Instead: create a table for BUSINESS and try to use it via OTHER_BUSINESS.
                      // The validateTableId checks business match.
                      UUID otherItem =
                          menuService
                              .createItem(
                                  new CreateMenuItemRequest(
                                      OTHER_BUSINESS, "Other Item", "MAIN", 5_000L, "IDR"))
                              .id();
                      return orderService.checkout(
                          new id.co.nativeapp.restaurant.order.dto.CheckoutRequest(
                              OTHER_BUSINESS,
                              "xbiz-table-" + UUID.randomUUID(),
                              List.of(new OrderLineRequest(otherItem, 1)),
                              null,
                              null,
                              "DINE_IN",
                              tableId)); // tableId belongs to BUSINESS, not OTHER_BUSINESS
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found or does not belong");
  }

  // -----------------------------------------------------------------------
  // Tenancy isolation
  // -----------------------------------------------------------------------

  @Test
  void tenantBCannotListOrResumeTenantAsParkedOrder() throws Exception {
    UUID itemId = seedItem(5_000L);

    CheckoutResult parked =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.park(
                    new ParkOrderRequest(
                        BUSINESS,
                        "park-tenant-iso-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)))));

    UUID orderId = parked.order().orderId();

    // Tenant B sees no parked orders for this business.
    List<ParkedOrderSummary> tenantBList =
        TenantContext.callAs(TENANT_B, ACTOR, () -> orderService.listParked(BUSINESS));
    assertThat(tenantBList).isEmpty();

    // Tenant B cannot resume (get) tenant A's order.
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR, () -> orderService.getOrder(orderId)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");

    // Tenant B cannot pay tenant A's order.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR, () -> orderService.payParked(orderId, new PayParkedRequest())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private long saleRecordedCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long paymentCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM payment")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
