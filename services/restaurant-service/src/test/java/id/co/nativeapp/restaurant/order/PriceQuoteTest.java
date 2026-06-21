package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
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
 * Integration tests for {@code POST /api/v1/orders/quote} (price-quote endpoint).
 *
 * <p>Proves:
 *
 * <ol>
 *   <li>A valid quote returns the correct price breakdown (subtotal / discount / SC / tax / grand
 *       total) for a known cart and surfaces {@code usesIllustrativeRules}.
 *   <li>A quote writes NOTHING: after a successful quote, the order, sale, payment, and outbox
 *       tables remain empty.
 *   <li>A quote with a discount reflects the discount in the breakdown (SC and tax recalculate on
 *       the reduced taxable base).
 *   <li>Quote validation rejects unknown menu items, inactive items, and cross-business items.
 *   <li>Tenancy isolation: tenant B cannot quote tenant A's items.
 * </ol>
 *
 * <p>Full {@code @SpringBootTest} context + Testcontainers PostgreSQL 16 (via {@link
 * PostgresRlsTestBase}). The illustrative SC (5%) and PB1 tax (10%) rules are seeded by Flyway.
 */
@SpringBootTest
class PriceQuoteTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final String ACTOR_B = "cashier-b@example.co.id";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUSINESS_B = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  // -----------------------------------------------------------------------
  // Correct breakdown
  // -----------------------------------------------------------------------

  /**
   * Quote for 2 × IDR 15,000 (subtotal 30,000) with illustrative rules: SC 5% on taxableBase, tax
   * 10% on (taxableBase + SC):
   *
   * <ul>
   *   <li>subtotal = 30,000
   *   <li>discount = 0
   *   <li>taxableBase = 30,000
   *   <li>SC = 30,000 × 5% = 1,500
   *   <li>taxBase = 30,000 + 1,500 = 31,500
   *   <li>tax = 31,500 × 10% = 3,150
   *   <li>grandTotal = 30,000 + 1,500 + 3,150 = 34,650
   * </ul>
   */
  @Test
  void quoteReturnsCorrectBreakdownForKnownCart() throws Exception {
    UUID itemId = seedItem(TENANT_A, ACTOR_A, BUSINESS_A, 15_000L);

    QuoteRequest request = new QuoteRequest(BUSINESS_A, List.of(new OrderLineRequest(itemId, 2)));

    PriceBreakdownResponse breakdown =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.quote(request));

    assertThat(breakdown.subtotalMinor()).isEqualTo(30_000L);
    assertThat(breakdown.discountMinor()).isEqualTo(0L);
    assertThat(breakdown.serviceChargeMinor()).isEqualTo(1_500L);
    assertThat(breakdown.taxMinor()).isEqualTo(3_150L);
    assertThat(breakdown.grandTotalMinor()).isEqualTo(34_650L);
    assertThat(breakdown.currency()).isEqualTo("IDR");
    assertThat(breakdown.usesIllustrativeRules()).isTrue();
  }

  /**
   * A quote writes NOTHING: order, sale, payment, and outbox tables must all be empty after a
   * successful quote (verified over the admin / BYPASSRLS connection).
   */
  @Test
  void quoteWritesNothingToAnyTable() throws Exception {
    UUID itemId = seedItem(TENANT_A, ACTOR_A, BUSINESS_A, 20_000L);

    QuoteRequest request = new QuoteRequest(BUSINESS_A, List.of(new OrderLineRequest(itemId, 1)));

    TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.quote(request));

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(rowCountAsAdmin("sale")).isZero();
    assertThat(rowCountAsAdmin("payment")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
  }

  // -----------------------------------------------------------------------
  // Discount handling
  // -----------------------------------------------------------------------

  /**
   * A quote with a fixed discount of 5,000 IDR reduces the taxable base and therefore the SC and
   * tax too:
   *
   * <ul>
   *   <li>subtotal = 30,000, discount = 5,000
   *   <li>taxableBase = 25,000
   *   <li>SC = 25,000 × 5% = 1,250
   *   <li>taxBase = 25,000 + 1,250 = 26,250
   *   <li>tax = 26,250 × 10% = 2,625
   *   <li>grandTotal = 25,000 + 1,250 + 2,625 = 28,875
   * </ul>
   */
  @Test
  void quoteWithDiscountReflectsDiscountInBreakdown() throws Exception {
    UUID itemId = seedItem(TENANT_A, ACTOR_A, BUSINESS_A, 15_000L);

    QuoteRequest request =
        new QuoteRequest(BUSINESS_A, List.of(new OrderLineRequest(itemId, 2)), 5_000L);

    PriceBreakdownResponse breakdown =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.quote(request));

    assertThat(breakdown.subtotalMinor()).isEqualTo(30_000L);
    assertThat(breakdown.discountMinor()).isEqualTo(5_000L);
    assertThat(breakdown.serviceChargeMinor()).isEqualTo(1_250L);
    assertThat(breakdown.taxMinor()).isEqualTo(2_625L);
    assertThat(breakdown.grandTotalMinor()).isEqualTo(28_875L);
  }

  /**
   * A discount that equals the subtotal is clamped — the breakdown carries a zero grand total,
   * which is valid for a quote (unlike checkout which rejects a zero total as "not a sale"). The
   * quote just prices the cart informatively.
   */
  @Test
  void quoteWithFullDiscountClampedToZeroGrandTotal() throws Exception {
    UUID itemId = seedItem(TENANT_A, ACTOR_A, BUSINESS_A, 10_000L);

    // discount equals subtotal: taxableBase = 0, SC = 0, tax = 0, grandTotal = 0
    QuoteRequest request =
        new QuoteRequest(BUSINESS_A, List.of(new OrderLineRequest(itemId, 1)), 10_000L);

    PriceBreakdownResponse breakdown =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.quote(request));

    assertThat(breakdown.discountMinor()).isEqualTo(10_000L);
    assertThat(breakdown.grandTotalMinor()).isEqualTo(0L);
    // Still writes nothing
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
  }

  // -----------------------------------------------------------------------
  // Validation: unknown / inactive / cross-business items
  // -----------------------------------------------------------------------

  @Test
  void quoteRejectsUnknownMenuItemWithIllegalArgumentException() {
    UUID nonExistentId = UUID.randomUUID();
    QuoteRequest request =
        new QuoteRequest(BUSINESS_A, List.of(new OrderLineRequest(nonExistentId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.quote(request)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found or not visible");
  }

  @Test
  void quoteRejectsInactiveMenuItemWithIllegalArgumentException() throws Exception {
    UUID itemId = seedItem(TENANT_A, ACTOR_A, BUSINESS_A, 12_000L);
    executeAsAdmin("UPDATE menu_item SET active = FALSE WHERE id = '" + itemId + "'");

    QuoteRequest request = new QuoteRequest(BUSINESS_A, List.of(new OrderLineRequest(itemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.quote(request)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inactive");
  }

  @Test
  void quoteRejectsCrossBusinessMenuItem() throws Exception {
    // Item belongs to BUSINESS_A but the quote is for BUSINESS_B
    UUID itemId = seedItem(TENANT_A, ACTOR_A, BUSINESS_A, 18_000L);

    QuoteRequest request = new QuoteRequest(BUSINESS_B, List.of(new OrderLineRequest(itemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.quote(request)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("belongs to business");
  }

  // -----------------------------------------------------------------------
  // Tenancy isolation
  // -----------------------------------------------------------------------

  /**
   * Tenant B cannot quote tenant A's menu items. Under tenant B's RLS scope, the item is invisible,
   * so the quote is rejected with "not found or not visible" — identical to the checkout tenancy
   * isolation guarantee.
   */
  @Test
  void tenantBCannotQuoteTenantAsMenuItem() throws Exception {
    UUID itemUnderA = seedItem(TENANT_A, ACTOR_A, BUSINESS_A, 25_000L);

    QuoteRequest crossTenantRequest =
        new QuoteRequest(BUSINESS_B, List.of(new OrderLineRequest(itemUnderA, 1)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> orderService.quote(crossTenantRequest)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found or not visible");

    // No data was written anywhere
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private UUID seedItem(String tenant, String actor, UUID businessId, long priceMinor)
      throws Exception {
    return TenantContext.callAs(
        tenant,
        actor,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(businessId, "Nasi Goreng", "MAIN", priceMinor, "IDR"))
                .id());
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

  private void executeAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(sql);
    }
  }
}
