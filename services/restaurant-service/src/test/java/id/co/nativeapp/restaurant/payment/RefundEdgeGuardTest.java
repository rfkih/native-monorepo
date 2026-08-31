package id.co.nativeapp.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.VoidRefundService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 2026-08-31 audit #2 — refunds are ALL-OR-NOTHING, once, for EVERY tender.
 *
 * <p>Finance's {@code ReversalPostingWriter} rejects any {@code SaleRefunded} below the sale's
 * grand total ({@code PartialRefundNotSupportedException} → DLT). Before the fix only ONLINE
 * payments were guarded at the producer edge; a partial CASH/QRIS/CARD refund returned 200 (drawer
 * + Z-report updated) while the GL silently kept the full revenue and clearing forever. The guard
 * now applies to every tender, and a second refund of an already-refunded payment is likewise
 * rejected.
 */
@SpringBootTest
class RefundEdgeGuardTest extends PostgresRlsTestBase {

  private static final String TENANT = "ab000001-ab00-ab00-ab00-ab0000000001";
  private static final String ACTOR = "refund-edge@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("ab000001-ab00-ab00-ab00-ab0000000002");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private VoidRefundService voidRefundService;

  @Test
  void partialCashRefundIsRejectedAtTheEdge() throws Exception {
    PaymentResponse payment = checkoutCash("Nasi Uduk");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        voidRefundService.refund(
                            payment.paymentId(),
                            Money.ofMinor(payment.amountMinor() - 1_000L, "IDR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("refunded in full");
  }

  @Test
  void fullRefundSucceedsAndASecondRefundIsRejected() throws Exception {
    PaymentResponse payment = checkoutCash("Ayam Geprek");

    PaymentResponse refunded =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                voidRefundService.refund(
                    payment.paymentId(), Money.ofMinor(payment.amountMinor(), "IDR")));
    assertThat(refunded.status()).isEqualTo("REFUNDED");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        voidRefundService.refund(
                            payment.paymentId(), Money.ofMinor(payment.amountMinor(), "IDR"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private PaymentResponse checkoutCash(String itemName) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          UUID itemId =
              menuService
                  .createItem(new CreateMenuItemRequest(BUSINESS, itemName, "MAIN", 20_000L, "IDR"))
                  .id();
          CheckoutRequest req =
              new CheckoutRequest(
                  BUSINESS,
                  "refund-edge-" + UUID.randomUUID(),
                  List.of(new OrderLineRequest(itemId, 1)),
                  new PaymentRequest(TenderType.CASH, 100_000L));
          return orderService.checkout(req).order().payment();
        });
  }
}
