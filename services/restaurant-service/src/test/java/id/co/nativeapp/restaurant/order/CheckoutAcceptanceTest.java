package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance test for the POS checkout flow.
 *
 * <p>Proves that checking out an order:
 *
 * <ol>
 *   <li>persists the order + its lines (verified over the admin/BYPASSRLS connection);
 *   <li>writes EXACTLY ONE {@code SaleRecorded} outbox row, atomically;
 *   <li>the {@code SaleRecorded} payload carries the correct total amount;
 *   <li>the order links back to the recorded sale id;
 *   <li>a retry with the same {@code idempotencyKey} returns the existing order and the outbox
 *       STILL holds exactly one {@code SaleRecorded} (idempotent on retry).
 * </ol>
 *
 * <p>Full context boots, so the auto-RLS aspect is active, Flyway has run (V1 + V2), and Hibernate
 * {@code ddl-auto=validate} has verified the mapping.
 */
@SpringBootTest
class CheckoutAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OTHER_BUSINESS_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void checkoutPersistsOrderAndWritesExactlyOneSaleRecordedAndIsIdempotentOnRetry()
      throws Exception {
    // Arrange: create a menu item in the tenant scope.
    UUID menuItemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest menuReq =
                  new CreateMenuItemRequest(BUSINESS_ID, "Nasi Goreng", "MAIN", 15_000L, "IDR");
              return menuService.createItem(menuReq).id();
            });

    CheckoutRequest checkoutReq =
        new CheckoutRequest(
            BUSINESS_ID, "order-idem-001", List.of(new OrderLineRequest(menuItemId, 2)));

    // Act: first checkout — creates the order + 1 SaleRecorded.
    CheckoutResult first =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(checkoutReq));

    assertThat(first.created()).isTrue();
    UUID orderId = first.order().orderId();
    // Phase 2 pricing: 2 × IDR 15,000 = subtotal 30,000 + SC 5% (1,500) + tax 10% of 31,500 (3,150)
    // grandTotal = 30,000 + 1,500 + 3,150 = 34,650. The demo tenant has illustrative rules seeded.
    assertThat(first.order().totalMinor()).isEqualTo(34_650L);
    assertThat(first.order().currency()).isEqualTo("IDR");
    assertThat(first.order().saleId()).isNotNull();
    assertThat(first.order().lines()).hasSize(1);
    assertThat(first.order().lines().get(0).name()).isEqualTo("Nasi Goreng");
    // Line total is the qty × unit price (before breakdown); grand total is on the order.
    assertThat(first.order().lines().get(0).lineTotalMinor()).isEqualTo(30_000L);

    // Assert: exactly one SaleRecorded in the outbox.
    List<Map<String, Object>> outboxRows = saleRecordedRows();
    assertThat(outboxRows).hasSize(1);
    Map<String, Object> outboxRow = outboxRows.getFirst();
    assertThat(outboxRow.get("event_type")).isEqualTo("SaleRecorded");
    assertThat(outboxRow.get("aggregate_type")).isEqualTo("sale");
    assertThat(outboxRow.get("company_id")).hasToString(TENANT_A);

    // The Avro payload carries the Phase 2 grand total and breakdown fields.
    GenericRecord decoded =
        AvroSerde.deserialize((byte[]) outboxRow.get("payload"), SaleRecordedSchema.schema());
    assertThat(decoded.get("amount_minor")).isEqualTo(34_650L); // grand total
    assertThat(decoded.get("subtotal_minor")).isEqualTo(30_000L);
    assertThat(decoded.get("service_charge_minor")).isEqualTo(1_500L);
    assertThat(decoded.get("tax_minor")).isEqualTo(3_150L);
    assertThat(decoded.get("discount_minor")).isEqualTo(0L); // zero discount (no promo applied)
    assertThat((Boolean) decoded.get("uses_illustrative_rules")).isTrue(); // illustrative rules
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("business_id").toString()).isEqualTo(BUSINESS_ID.toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(TENANT_A);

    // Verify rows in the DB (over admin connection — FORCE RLS would block without a tenant GUC).
    assertThat(rowCountAsAdmin("restaurant_order")).isEqualTo(1L);
    assertThat(rowCountAsAdmin("order_line")).isEqualTo(1L);

    // Act: retry with the same idempotency key — no second event.
    CheckoutResult retry =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(checkoutReq));

    assertThat(retry.created()).isFalse();
    assertThat(retry.order().orderId()).isEqualTo(orderId);
    assertThat(retry.order().totalMinor()).isEqualTo(34_650L);

    // Still exactly one SaleRecorded after the retry.
    assertThat(saleRecordedRows()).hasSize(1);
    assertThat(rowCountAsAdmin("restaurant_order")).isEqualTo(1L);
  }

  @Test
  void checkoutRejectsUnknownMenuItemWithIllegalArgumentException() throws Exception {
    UUID nonExistentItemId = UUID.randomUUID();
    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID, "order-idem-bad", List.of(new OrderLineRequest(nonExistentItemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found or not visible");
  }

  @Test
  void checkoutRejectsAnInactiveMenuItem() throws Exception {
    // Create an active item, then deactivate it over the admin/BYPASSRLS connection (there is no
    // deactivate endpoint yet) so the row flips regardless of any session tenant GUC. The
    // findViewsByIds load has no active filter, so the item is returned and the OrderWriter guard
    // is what must reject it.
    UUID menuItemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Soto Ayam", "MAIN", 18_000L, "IDR");
              return menuService.createItem(req).id();
            });
    executeAsAdmin("UPDATE menu_item SET active = FALSE WHERE id = '" + menuItemId + "'");

    CheckoutRequest req =
        new CheckoutRequest(
            BUSINESS_ID, "order-idem-inactive", List.of(new OrderLineRequest(menuItemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inactive");

    // The guard fires before any write — nothing persisted, no event emitted.
    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(saleRecordedRows()).isEmpty();
  }

  @Test
  void checkoutRejectsAMenuItemFromAnotherBusiness() throws Exception {
    // Item belongs to BUSINESS_ID (business A) within tenant A.
    UUID menuItemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CreateMenuItemRequest req =
                  new CreateMenuItemRequest(BUSINESS_ID, "Bakso", "MAIN", 20_000L, "IDR");
              return menuService.createItem(req).id();
            });

    // Checkout routed to a DIFFERENT business in the SAME tenant: the item is visible under RLS
    // (same company), so it passes the not-found check — the W4 business-match guard must reject
    // it.
    CheckoutRequest req =
        new CheckoutRequest(
            OTHER_BUSINESS_ID, "order-idem-xbiz", List.of(new OrderLineRequest(menuItemId, 1)));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> orderService.checkout(req)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("belongs to business");

    assertThat(rowCountAsAdmin("restaurant_order")).isZero();
    assertThat(saleRecordedRows()).isEmpty();
  }

  private List<Map<String, Object>> saleRecordedRows() {
    return jdbcTemplate.queryForList(
        "SELECT event_type, aggregate_type, aggregate_id, company_id, payload "
            + "FROM outbox WHERE event_type = 'SaleRecorded' ORDER BY occurred_at");
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
