package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Follow-up to the sales-history reprint flow ({@code GET /api/v1/orders/{id}}): proves the ORDER
 * READ PATH ({@code OrderWriter.findById} / {@code OrderService.getOrder}) populates {@code
 * payment} from a FRESH fetch — not only from the checkout/pay-parked response the till already had
 * in hand — so a receipt can be rebuilt after the fact. {@code breakdown} is deliberately left
 * {@code null} on this path (the tax/service-charge split is not persisted per order and must not
 * be recomputed from current rules — out of scope here, see {@code OrderWriter.findById} javadoc).
 */
@SpringBootTest
class OrderReadPathPaymentTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-a@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS, "Es Teh", "DRINK", priceMinor, "IDR"))
                .id());
  }

  @Test
  void aFreshGetOrderReturnsThePaymentThatSettledIt() throws Exception {
    UUID itemId = seedItem(15_000L);

    // Tender comfortably covers whatever the seeded tax/service-charge rules produce as the grand
    // total — the exact figure is not the point here (CashPaymentAcceptanceTest already pins that
    // math); this test's oracle is that a SEPARATE, later fetch sees the SAME payment the write
    // path already attached.
    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "reprint-cash-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)),
                        new PaymentRequest(TenderType.CASH, 1_000_000L))));
    UUID orderId = checkout.order().orderId();
    assertThat(checkout.order().payment())
        .as("sanity: the write path already attaches the payment it just captured")
        .isNotNull();

    // A SEPARATE, fresh fetch — simulating the reprint flow reopening the order later, not just
    // reusing the checkout response the till already had.
    OrderResponse fetched =
        TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.getOrder(orderId));

    assertThat(fetched.payment())
        .as("read path must populate payment so a receipt can be rebuilt")
        .isEqualTo(checkout.order().payment());
    assertThat(fetched.saleId()).isEqualTo(checkout.order().saleId());
    // breakdown intentionally NOT recomputed on this path (out of scope for this fix).
    assertThat(fetched.breakdown()).isNull();
  }

  @Test
  void aParkedUnpaidOrderReturnsNullPaymentOnGet() throws Exception {
    UUID itemId = seedItem(10_000L);

    CheckoutResult parked =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.park(
                    new ParkOrderRequest(
                        BUSINESS,
                        "reprint-parked-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)))));
    UUID orderId = parked.order().orderId();

    OrderResponse fetched =
        TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.getOrder(orderId));

    assertThat(fetched.payment()).as("a parked, unpaid order has no payment to attach").isNull();
  }

  @Test
  void getOrderReturnsTheNewestPaymentWhenTheOrderHasSeveralRows() throws Exception {
    UUID itemId = seedItem(12_000L);

    CheckoutResult checkout =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS,
                        "reprint-multi-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 1)),
                        new PaymentRequest(TenderType.CASH, 1_000_000L))));
    UUID orderId = checkout.order().orderId();
    UUID firstPaymentId = checkout.order().payment().paymentId();

    // Simulate a voided+retried tender producing a SECOND payment row against the same order,
    // stamped with a LATER created_at (there is no public retry endpoint today — inserted directly
    // over the admin/BYPASSRLS connection, test-only whole-row write, CLAUDE.md exempts src/test).
    UUID secondPaymentId = UUID.randomUUID();
    Timestamp later = Timestamp.from(Instant.now().plusSeconds(60));
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO payment (id, order_id, business_id, tender_type, status,"
                    + " amount_minor, currency, tendered_minor, change_minor, refunded_minor,"
                    + " provider_pending, sale_id, occurred_at, idempotency_key,"
                    + " created_at, created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'CARD', 'CAPTURED', 12000, 'IDR', NULL, NULL, 0,"
                    + " FALSE, NULL, ?, ?, ?, 'test', ?, 'test', 0, ?)")) {
      ps.setObject(1, secondPaymentId);
      ps.setObject(2, orderId);
      ps.setObject(3, BUSINESS);
      ps.setTimestamp(4, later);
      ps.setString(5, "reprint-multi-retry-" + secondPaymentId);
      ps.setTimestamp(6, later);
      ps.setTimestamp(7, later);
      ps.setString(8, TENANT_A);
      ps.executeUpdate();
    }

    OrderResponse fetched =
        TenantContext.callAs(TENANT_A, ACTOR, () -> orderService.getOrder(orderId));

    assertThat(fetched.payment()).isNotNull();
    assertThat(fetched.payment().paymentId())
        .as("the newest (created_at DESC) payment row wins")
        .isEqualTo(secondPaymentId)
        .isNotEqualTo(firstPaymentId);
    assertThat(fetched.payment().tenderType()).isEqualTo("CARD");
  }
}
