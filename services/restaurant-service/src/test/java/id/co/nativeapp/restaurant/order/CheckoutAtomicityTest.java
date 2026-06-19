package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.service.PostOutboxHook;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Atomicity (rule 3, C2) for the checkout path: the order, its lines, the sale, and the {@code
 * SaleRecorded} outbox row must all commit together — or all roll back together.
 *
 * <p>Mirrors {@link id.co.nativeapp.restaurant.RecordSaleAtomicityTest} for the checkout flow. A
 * test-only {@link PostOutboxHook} is installed that throws AFTER the outbox write but still inside
 * {@link id.co.nativeapp.restaurant.order.service.OrderWriter#checkout}'s single {@code
 * REQUIRES_NEW} transaction. With C1 fixed (sale + outbox write join the order's transaction via
 * {@code MANDATORY}), the failure rolls back {@code restaurant_order}, {@code order_line}, {@code
 * sale}, and {@code outbox} together — none of them survive.
 *
 * <p>If the old code (sale in a separate {@code REQUIRES_NEW}) were used, this test would fail: the
 * sale + outbox rows would survive in their committed subtransaction even after the order
 * transaction rolled back — proving the torn-write bug.
 */
@SpringBootTest
class CheckoutAtomicityTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  static final String BOOM = "forced failure after outbox write (checkout atomicity test)";

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  @Test
  void aFailureAfterTheOutboxWriteRollsBackOrderLinesSaleAndOutboxTogether() throws Exception {
    // Arrange: create a menu item inside the tenant scope.
    UUID menuItemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Nasi Goreng", "MAIN", 15_000L, "IDR");
              return menuService.createItem(req).id();
            });

    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_ID, "atomic-order-key", List.of(new OrderLineRequest(menuItemId, 2)));

    // Act: checkout blows up after writing the outbox row, inside the transaction.
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(checkoutReq)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BOOM);

    // All four writes rolled back together — nothing persists. Counted over the
    // admin (BYPASSRLS) connection because the tables are FORCE RLS.
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(rowCountAsAdmin("order_line")).isZero();
    assertThat(rowCountAsAdmin("sale")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
  }

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

  /**
   * Installs a {@link PostOutboxHook} that throws after the outbox write. {@link Primary} so it
   * wins injection into {@link id.co.nativeapp.restaurant.sale.service.SaleWriter SaleWriter},
   * making this test's Spring context distinct from the no-op production context (so the throwing
   * hook never leaks into other {@code @SpringBootTest} classes).
   */
  @TestConfiguration
  static class ThrowingHookConfig {

    @Bean
    @Primary
    PostOutboxHook throwingPostOutboxHookForCheckout() {
      return (Sale sale) -> {
        throw new IllegalStateException(BOOM);
      };
    }
  }
}
