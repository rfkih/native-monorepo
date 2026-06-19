package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real (Testcontainers) mixed-currency rejection test (W3).
 *
 * <p>Seeds TWO menu items for the same tenant and business but in DIFFERENT currencies (IDR and
 * USD), then asserts that a checkout referencing both items is rejected by {@link
 * id.co.nativeapp.restaurant.order.service.OrderWriter OrderWriter} with an {@link
 * IllegalArgumentException} whose message identifies the currency conflict. This exercises the real
 * validation path — not a mock — against an actual PostgreSQL 16 database.
 *
 * <p>Rule 8: money is a single currency per order; mixing currencies is a domain error mapped to
 * 400 by {@link id.co.nativeapp.security.ApiExceptionHandler ApiExceptionHandler}.
 */
@SpringBootTest
class CheckoutMixedCurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  @Test
  void checkoutWithTwoItemsInDifferentCurrenciesIsRejected() throws Exception {
    // Seed one IDR item and one USD item under the same tenant + business.
    UUID idrItemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Nasi Goreng", "MAIN", 15_000L, "IDR");
              return menuService.createItem(req).id();
            });

    UUID usdItemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Burger", "MAIN", 8L, "USD");
              return menuService.createItem(req).id();
            });

    // Attempt a checkout that spans both currencies — must be rejected.
    CheckoutRequest mixedReq =
        new CheckoutRequest(
            BUSINESS_ID,
            "mixed-ccy-key",
            List.of(new OrderLineRequest(idrItemId, 1), new OrderLineRequest(usdItemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(mixedReq)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same currency")
        .hasMessageContaining("IDR")
        .hasMessageContaining("USD");
  }
}
