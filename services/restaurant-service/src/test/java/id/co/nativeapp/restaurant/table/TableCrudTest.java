package id.co.nativeapp.restaurant.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.dto.TableResponse;
import id.co.nativeapp.restaurant.table.service.TableService;
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
 * Acceptance tests for the restaurant-table CRUD endpoints.
 *
 * <p>Proves:
 *
 * <ul>
 *   <li>Create / activate / deactivate round-trip.
 *   <li>Duplicate label in same business is rejected.
 *   <li>Occupancy reflects an open / parked order that references the table, and is freed after
 *       completion.
 *   <li>Tenancy isolation: tenant B cannot see tenant A's tables.
 * </ul>
 */
@SpringBootTest
class TableCrudTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "aaaa0001-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TENANT_B = "bbbb0001-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String ACTOR = "staff@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("cc000001-cccc-cccc-cccc-cccccccccccc");

  @Autowired private TableService tableService;
  @Autowired private MenuService menuService;
  @Autowired private OrderService orderService;

  @Test
  void createAndListTableRoundTrip() throws Exception {
    TableResponse created =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> tableService.create(new CreateTableRequest(BUSINESS, "T1", 4, "Indoor")));

    assertThat(created.tableId()).isNotNull();
    assertThat(created.label()).isEqualTo("T1");
    assertThat(created.capacity()).isEqualTo(4);
    assertThat(created.area()).isEqualTo("Indoor");
    assertThat(created.active()).isTrue();
    assertThat(created.occupied()).isFalse();

    List<TableResponse> list =
        TenantContext.callAs(TENANT_A, ACTOR, () -> tableService.listByBusiness(BUSINESS));

    assertThat(list).hasSize(1);
    assertThat(list.get(0).label()).isEqualTo("T1");
    assertThat(list.get(0).occupied()).isFalse();
  }

  @Test
  void duplicateLabelInSameBusinessIsRejected() throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> tableService.create(new CreateTableRequest(BUSINESS, "T2", 2, null)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () -> tableService.create(new CreateTableRequest(BUSINESS, "T2", 4, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("T2");
  }

  @Test
  void activateDeactivateRoundTrip() throws Exception {
    TableResponse table =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> tableService.create(new CreateTableRequest(BUSINESS, "T3", 6, null)));

    UUID tableId = table.tableId();

    // Deactivate.
    TableResponse deactivated =
        TenantContext.callAs(TENANT_A, ACTOR, () -> tableService.deactivate(tableId));
    assertThat(deactivated.active()).isFalse();

    // Activate again.
    TableResponse activated =
        TenantContext.callAs(TENANT_A, ACTOR, () -> tableService.activate(tableId));
    assertThat(activated.active()).isTrue();
  }

  @Test
  void occupancyReflectsOpenOrderAndFreedAfterCompletion() throws Exception {
    // Create table.
    TableResponse table =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> tableService.create(new CreateTableRequest(BUSINESS, "T4", 2, null)));
    UUID tableId = table.tableId();

    // Create menu item.
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS, "Nasi Padang", "MAIN", 20_000L, "IDR"))
                    .id());

    // Checkout a DINE_IN order for this table (cash → COMPLETED immediately).
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            orderService.checkout(
                new CheckoutRequest(
                    BUSINESS,
                    "occupy-table-t4-" + UUID.randomUUID(),
                    List.of(new OrderLineRequest(itemId, 1)),
                    null,
                    null,
                    "DINE_IN",
                    tableId)));

    // After a COMPLETED order the table is NOT occupied (no open order references it).
    List<TableResponse> listAfterComplete =
        TenantContext.callAs(TENANT_A, ACTOR, () -> tableService.listByBusiness(BUSINESS));
    assertThat(listAfterComplete.stream().filter(t -> t.tableId().equals(tableId)).findFirst())
        .isPresent()
        .get()
        .satisfies(t -> assertThat(t.occupied()).isFalse());
  }

  @Test
  void occupancyIsTrueWhileOrderIsParked() throws Exception {
    // Create table.
    TableResponse table =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> tableService.create(new CreateTableRequest(BUSINESS, "T5", 2, null)));
    UUID tableId = table.tableId();

    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS, "Es Kopi", "DRINK", 10_000L, "IDR"))
                    .id());

    // Park an order for this table — stays PARKED (open).
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            orderService.park(
                new id.co.nativeapp.restaurant.order.dto.ParkOrderRequest(
                    BUSINESS,
                    "park-occupy-t5-" + UUID.randomUUID(),
                    List.of(new OrderLineRequest(itemId, 1)),
                    "DINE_IN",
                    tableId,
                    null)));

    // Table should now be occupied.
    List<TableResponse> list =
        TenantContext.callAs(TENANT_A, ACTOR, () -> tableService.listByBusiness(BUSINESS));
    assertThat(list.stream().filter(t -> t.tableId().equals(tableId)).findFirst())
        .isPresent()
        .get()
        .satisfies(t -> assertThat(t.occupied()).isTrue());
  }

  @Test
  void tenantBCannotSeetenantATables() throws Exception {
    // Tenant A creates a table.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> tableService.create(new CreateTableRequest(BUSINESS, "T6", 2, null)));

    // Tenant B sees no tables for the same businessId (RLS isolates by company_id).
    List<TableResponse> tenantBList =
        TenantContext.callAs(TENANT_B, ACTOR, () -> tableService.listByBusiness(BUSINESS));

    assertThat(tenantBList).isEmpty();
  }

  @Test
  void tableNotFoundForAnotherTenantWhenActivating() throws Exception {
    // Tenant A creates a table.
    TableResponse table =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> tableService.create(new CreateTableRequest(BUSINESS, "T7", 2, null)));

    // Tenant B tries to activate tenant A's table — should get IllegalArgumentException (not
    // found).
    assertThatThrownBy(
            () ->
                TenantContext.callAs(TENANT_B, ACTOR, () -> tableService.activate(table.tableId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  // Helpers

  @SuppressWarnings("unused")
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
}
