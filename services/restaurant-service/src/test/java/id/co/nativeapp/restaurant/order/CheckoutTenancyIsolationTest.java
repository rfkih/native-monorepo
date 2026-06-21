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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tenancy isolation (W2) for the checkout path, mirroring {@link
 * id.co.nativeapp.restaurant.SaleTenancyIsolationTest}.
 *
 * <p>Proves two things:
 *
 * <ol>
 *   <li>Tenant B cannot check out a menu item that belongs to tenant A: the item is invisible under
 *       tenant B's RLS scope, so the checkout is rejected with "not found or not visible".
 *   <li>After the failed cross-tenant attempt, tenant A's data (its menu item and any legitimate
 *       orders) remains intact and is never leaked to tenant B.
 * </ol>
 *
 * <p>No manual {@code WHERE company_id} anywhere in the service or repository — only the
 * auto-applied RLS policy (via {@link id.co.nativeapp.tenant.RlsAutoApplyAspect}) enforces the
 * boundary (rule 5). This models a developer who forgets the tenant filter and proves they are
 * still protected.
 */
@SpringBootTest
class CheckoutTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final String ACTOR_B = "cashier-b@example.co.id";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUSINESS_B = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  @Test
  void tenantBCannotCheckOutTenantAsMenuItem() throws Exception {
    // Seed a menu item under tenant A only.
    UUID menuItemUnderA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_A, "Nasi Goreng", "MAIN", 15_000L, "IDR");
              return menuService.createItem(req).id();
            });

    // Tenant B attempts to check out using tenant A's menu item id.
    // Under B's RLS scope the item is invisible — must be rejected as "not found".
    CheckoutRequest crossTenantReq =
        new CheckoutRequest(
            BUSINESS_B, "cross-tenant-key", List.of(new OrderLineRequest(menuItemUnderA, 1)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> orderService.checkout(crossTenantReq)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found or not visible");

    // Tenant A's data is untouched — no order was created for any tenant.
    assertThat(orderRowCountAsAdmin()).isZero();
    assertThat(saleRowCountAsAdmin()).isZero();
  }

  @Test
  void tenantACanSuccessfullyCheckOutItsOwnMenuItem() throws Exception {
    // Baseline: tenant A can check out its own item without issue.
    UUID menuItemUnderA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_A, "Mie Goreng", "MAIN", 12_000L, "IDR");
              return menuService.createItem(req).id();
            });

    CheckoutRequest ownReq =
        new CheckoutRequest(
            BUSINESS_A, "own-tenant-key", List.of(new OrderLineRequest(menuItemUnderA, 1)));

    CheckoutResult result =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(ownReq));

    assertThat(result.created()).isTrue();
    // Phase 2 pricing: subtotal 12,000 | SC 5% = 600 | taxBase = 12,600 | tax 10% = 1,260
    // grandTotal = 12,000 + 600 + 1,260 = 13,860 (demo tenant has illustrative rules seeded).
    assertThat(result.order().totalMinor()).isEqualTo(13_860L);
    assertThat(result.order().saleId()).isNotNull();
  }

  private long orderRowCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM restaurant_order")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long saleRowCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM sale")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
