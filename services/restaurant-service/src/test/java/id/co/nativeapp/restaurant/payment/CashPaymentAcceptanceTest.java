package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
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
 * Acceptance test for paying an order with CASH at checkout (ADR 0006).
 *
 * <p>Proves a cash checkout: captures the payment synchronously with correct change; records
 * exactly one {@code SaleRecorded} (cash recognises revenue immediately, atomic with the order);
 * links the payment to the sale; and that an insufficient / missing tender is rejected and rolls
 * the whole checkout back (no order, no sale, no payment) — the atomicity guarantee.
 */
@SpringBootTest
class CashPaymentAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(
                        BUSINESS_ID, "Nasi Goreng", "MAIN", priceMinor, "IDR"))
                .id());
  }

  @Test
  void cashCheckoutCapturesPaymentWithChangeAndRecordsOneSale() throws Exception {
    UUID itemId = seedItem(15_000L);
    // Phase 2 pricing (demo tenant has illustrative rules seeded):
    // subtotal = 2 × 15,000 = 30,000 | SC 5% = 1,500 | taxBase = 31,500 | tax 10% = 3,150
    // grandTotal = 30,000 + 1,500 + 3,150 = 34,650
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "cash-001",
            List.of(new OrderLineRequest(itemId, 2)), // subtotal 30,000; grandTotal 34,650
            new PaymentRequest(TenderType.CASH, 50_000L)); // change = 50,000 - 34,650 = 15,350

    CheckoutResult result =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    OrderResponse order = result.order();
    assertThat(order.totalMinor()).isEqualTo(34_650L);
    assertThat(order.saleId()).isNotNull();

    PaymentResponse payment = order.payment();
    assertThat(payment).isNotNull();
    assertThat(payment.tenderType()).isEqualTo("CASH");
    assertThat(payment.status()).isEqualTo("CAPTURED");
    assertThat(payment.amountMinor()).isEqualTo(34_650L);
    assertThat(payment.currency()).isEqualTo("IDR");
    assertThat(payment.tenderedMinor()).isEqualTo(50_000L);
    assertThat(payment.changeMinor()).isEqualTo(15_350L);
    assertThat(payment.providerPending()).isFalse();
    assertThat(payment.saleId()).isEqualTo(order.saleId());

    // Cash records the sale synchronously: exactly one captured payment + one SaleRecorded.
    assertThat(rowCountAsAdmin("payment")).isEqualTo(1L);
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);
  }

  @Test
  void exactCashLeavesZeroChange() throws Exception {
    UUID itemId = seedItem(12_000L);
    // Phase 2 pricing: subtotal = 12,000 | SC 5% = 600 | taxBase = 12,600 | tax 10% = 1,260
    // grandTotal = 12,000 + 600 + 1,260 = 13,860. Tender exactly 13,860 for zero change.
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "cash-exact",
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, 13_860L)); // exact grandTotal

    CheckoutResult result =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req));

    assertThat(result.order().payment().changeMinor()).isZero();
    assertThat(result.order().payment().status()).isEqualTo("CAPTURED");
  }

  @Test
  void insufficientTenderIsRejectedAndRollsBackTheWholeCheckout() throws Exception {
    UUID itemId = seedItem(40_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "cash-short",
            List.of(new OrderLineRequest(itemId, 1)), // total 40_000
            new PaymentRequest(TenderType.CASH, 30_000L)); // not enough

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("less than the amount due");

    // The payment failure rolls back the whole REQUIRES_NEW checkout tx — nothing persisted.
    assertThat(rowCountAsAdmin("payment")).isZero();
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(saleRecordedCountAsAdmin()).isZero();
  }

  @Test
  void missingTenderedAmountForCashIsRejected() throws Exception {
    UUID itemId = seedItem(10_000L);
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "cash-notendered",
            List.of(new OrderLineRequest(itemId, 1)),
            new PaymentRequest(TenderType.CASH, null));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tendered");
  }

  @Test
  void checkoutWithoutPaymentStillWorks() throws Exception {
    UUID itemId = seedItem(25_000L);
    CheckoutRequest req =
        new CheckoutRequest(BUSINESS_ID, "no-payment", List.of(new OrderLineRequest(itemId, 1)));

    CheckoutResult result =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req));

    assertThat(result.created()).isTrue();
    assertThat(result.order().payment()).isNull();
    assertThat(rowCountAsAdmin("payment")).isZero();
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);
  }

  // ---------------------------------------------------------------- admin helpers

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

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
}
