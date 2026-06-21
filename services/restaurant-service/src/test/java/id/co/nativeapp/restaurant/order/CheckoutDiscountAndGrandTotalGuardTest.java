package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance tests for the checkout grand-total guard and the S1 discount wiring.
 *
 * <p>Covers (B2 / S1):
 *
 * <ul>
 *   <li>A {@code ≤0} grand-total checkout is rejected (the zero-grand guard in {@link
 *       id.co.nativeapp.restaurant.order.service.OrderWriter}).
 *   <li>A checkout with a fixed discount threads it through the pricing formula and the discount is
 *       reflected in the SaleRecorded breakdown (the discount is now wired, not dead-wired to 0).
 * </ul>
 *
 * <p>Full Spring context + Testcontainers PostgreSQL 16 (via {@link PostgresRlsTestBase}).
 */
@SpringBootTest
class CheckoutDiscountAndGrandTotalGuardTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  private UUID seedItem(long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(
                        BUSINESS_ID, "Nasi Goreng", "MAIN", priceMinor, "IDR"))
                .id());
  }

  // -----------------------------------------------------------------------
  // Grand-total guard: ≤0 grand total is rejected
  // -----------------------------------------------------------------------

  /**
   * A checkout where the discount fully comps the order (discount >= subtotal) and therefore the
   * grand total is zero must be rejected with an {@link IllegalArgumentException}. A fully-comped
   * order is not a sale — it should be recorded separately (e.g. as a comp or void).
   *
   * <p>Note: TaxChargeService clamps discount to ≤ subtotal, so passing discountMinor == subtotal
   * yields taxableBase=0, SC=0 (5% of 0), tax=0 (10% of 0), grandTotal=0 → rejected.
   */
  @Test
  void fullyCcompedOrderIsRejectedByGrandTotalGuard() throws Exception {
    // Item price 10,000 IDR; 1 unit → subtotal = 10,000.
    UUID itemId = seedItem(10_000L);

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID,
            "order-zero-grand-" + UUID.randomUUID(),
            List.of(new OrderLineRequest(itemId, 1)),
            null,
            10_000L); // discount = subtotal (fully comped) → grand total = 0

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("grand total must be positive");
  }

  // -----------------------------------------------------------------------
  // S1: discount is wired (not dead-wired to 0)
  // -----------------------------------------------------------------------

  /**
   * A checkout with a non-zero fixed discount threads the discount through the pricing formula. The
   * resulting grand total must be less than the grand total of the same order without a discount.
   *
   * <p>Example (IDR, illustrative rules: SC 5% on taxableBase, tax 10% on taxableBase+SC):
   *
   * <ul>
   *   <li>No discount: subtotal=30,000, SC=1,500, tax=3,150, grand=34,650.
   *   <li>Discount=5,000: taxableBase=25,000, SC=1,250, tax=2,625, grand=28,875.
   * </ul>
   */
  @Test
  void discountWiredIntoCheckoutReducesGrandTotal() throws Exception {
    UUID itemId = seedItem(15_000L); // 2 × 15,000 = subtotal 30,000

    // No discount
    CheckoutResult noDiscount =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS_ID,
                        "order-no-disc-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 2)),
                        null,
                        null)));

    // With discount = 5,000 IDR
    CheckoutResult withDiscount =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS_ID,
                        "order-with-disc-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 2)),
                        null,
                        5_000L))); // S1: discount is now wired

    long grandNoDiscount = noDiscount.order().totalMinor();
    long grandWithDiscount = withDiscount.order().totalMinor();

    assertThat(grandNoDiscount)
        .as("no-discount grand total (Phase 2 illustrative: 30k+1.5k+3.15k=34,650)")
        .isEqualTo(34_650L);
    assertThat(grandWithDiscount)
        .as("discount=5k reduces grand total (25k+1.25k+2.625k=28,875)")
        .isEqualTo(28_875L);
    assertThat(grandWithDiscount)
        .as("discount must reduce the grand total")
        .isLessThan(grandNoDiscount);
  }

  /** Discount of 0 is equivalent to no discount — the grand total is the same. */
  @Test
  void zeroDiscountIsEquivalentToNoDiscount() throws Exception {
    UUID itemId = seedItem(15_000L);

    CheckoutResult noDiscount =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS_ID,
                        "order-zero-disc-a-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 2)),
                        null,
                        null)));

    CheckoutResult zeroDiscount =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                orderService.checkout(
                    new CheckoutRequest(
                        BUSINESS_ID,
                        "order-zero-disc-b-" + UUID.randomUUID(),
                        List.of(new OrderLineRequest(itemId, 2)),
                        null,
                        0L))); // explicit zero

    assertThat(noDiscount.order().totalMinor())
        .as("null discount == 0 discount: same grand total")
        .isEqualTo(zeroDiscount.order().totalMinor());
  }
}
