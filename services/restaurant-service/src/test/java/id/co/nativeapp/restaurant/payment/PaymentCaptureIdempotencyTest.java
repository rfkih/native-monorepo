package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
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
 * Verifies idempotency of the capture endpoint (ADR 0006, slice 3): a second capture call for the
 * same payment must return the same result and must NOT emit a second {@code SaleRecorded}.
 */
@SpringBootTest
class PaymentCaptureIdempotencyTest extends PostgresRlsTestBase {

  private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-111111111111";
  private static final String ACTOR = "cashier-e@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("ffffffff-ffff-ffff-ffff-111111111111");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private PaymentCaptureService captureService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS, "Kopi", "DRINK", priceMinor, "IDR"))
                .id());
  }

  @Test
  void secondCaptureIsIdempotentAndEmitsNoSecondSaleRecorded() throws Exception {
    UUID itemId = seedItem(6_000L);
    UUID paymentId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService
                    .checkout(
                        new CheckoutRequest(
                            BUSINESS,
                            "capture-idem-001",
                            List.of(new OrderLineRequest(itemId, 1)),
                            new PaymentRequest(TenderType.QRIS, null)))
                    .order()
                    .payment()
                    .paymentId());

    // First capture.
    PaymentResponse first =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));
    assertThat(first.status()).isEqualTo("CAPTURED");
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);

    // Second capture (re-delivery) — must be idempotent.
    PaymentResponse second =
        TenantContext.callAs(TENANT, ACTOR, () -> captureService.capture(paymentId));
    assertThat(second.status()).isEqualTo("CAPTURED");
    assertThat(second.saleId()).isEqualTo(first.saleId());

    // No second SaleRecorded emitted.
    assertThat(saleRecordedCountAsAdmin())
        .as("second capture must not emit a second SaleRecorded")
        .isEqualTo(1L);
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
