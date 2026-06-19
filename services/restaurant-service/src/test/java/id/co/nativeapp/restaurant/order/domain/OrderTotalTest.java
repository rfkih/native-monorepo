package id.co.nativeapp.restaurant.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain/unit tests for order total computation and currency-mix rejection.
 *
 * <p>These run as pure JUnit — no Spring context, no DB (they are domain invariant tests). They
 * prove:
 *
 * <ul>
 *   <li>line totals are computed via exact integer arithmetic (never a float);
 *   <li>order totals accumulate correctly across lines;
 *   <li>a {@code qty < 1} is rejected in the {@link OrderLine} constructor;
 *   <li>currency mixing is detected and rejected at the service level (proven in {@link
 *       id.co.nativeapp.restaurant.order.CheckoutMixedCurrencyTest CheckoutMixedCurrencyTest} and
 *       the acceptance test).
 * </ul>
 */
class OrderTotalTest {

  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant NOW = Instant.parse("2026-06-18T10:00:00Z");

  @Test
  void singleLineOrderTotalEqualsUnitPriceTimesQty() {
    Money unitPrice = Money.ofMinor(15_000L, "IDR");
    OrderLine line = new OrderLine(UUID.randomUUID(), "Nasi Goreng", unitPrice, 2);

    assertThat(line.getLineTotalMinor()).isEqualTo(30_000L);
    assertThat(line.getUnitPriceMinor()).isEqualTo(15_000L);
    assertThat(line.getQty()).isEqualTo(2);
  }

  @Test
  void multiLineOrderTotalIsExactSumOfLineTotals() {
    Money price1 = Money.ofMinor(10_000L, "IDR");
    Money price2 = Money.ofMinor(25_000L, "IDR");
    OrderLine line1 = new OrderLine(UUID.randomUUID(), "Item A", price1, 3); // 30_000
    OrderLine line2 = new OrderLine(UUID.randomUUID(), "Item B", price2, 1); // 25_000

    Money total =
        Money.zero(java.util.Currency.getInstance("IDR"))
            .plus(Money.ofMinor(line1.getLineTotalMinor(), "IDR"))
            .plus(Money.ofMinor(line2.getLineTotalMinor(), "IDR"));

    assertThat(total.amountMinor()).isEqualTo(55_000L);
  }

  @Test
  void qtyBelowOneIsRejectedByOrderLine() {
    Money price = Money.ofMinor(10_000L, "IDR");
    assertThatThrownBy(() -> new OrderLine(UUID.randomUUID(), "Item", price, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("qty must be >= 1");
  }

  @Test
  void negativeQtyIsRejectedByOrderLine() {
    Money price = Money.ofMinor(10_000L, "IDR");
    assertThatThrownBy(() -> new OrderLine(UUID.randomUUID(), "Item", price, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("qty must be >= 1");
  }

  @Test
  void orderLinksTheSaleIdOnCheckout() {
    Money total = Money.ofMinor(50_000L, "IDR");
    Order order = new Order(BUSINESS_ID, total, NOW, "idem-key-1");
    UUID saleId = UUID.randomUUID();
    order.linkSale(saleId);

    assertThat(order.getSaleId()).isEqualTo(saleId);
    assertThat(order.getStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void orderStartsInPendingStatus() {
    Money total = Money.ofMinor(50_000L, "IDR");
    Order order = new Order(BUSINESS_ID, total, NOW, "idem-key-2");
    assertThat(order.getStatus()).isEqualTo("PENDING");
    assertThat(order.getSaleId()).isNull();
  }
}
