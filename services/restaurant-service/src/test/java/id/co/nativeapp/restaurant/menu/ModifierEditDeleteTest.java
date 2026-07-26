package id.co.nativeapp.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierOptionResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.UpdateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for the edit (PATCH) and delete (DELETE) operations on modifier groups and
 * options.
 *
 * <p>Covers:
 *
 * <ol>
 *   <li>PATCH group — rename.
 *   <li>PATCH group — change selectionType + min/max.
 *   <li>PATCH group — rejects invalid min/max (minSelect > maxSelect).
 *   <li>PATCH group — rejects SINGLE type with maxSelect > 1.
 *   <li>PATCH option — changes name and priceDeltaMinor.
 *   <li>PATCH option — changes displayOrder only.
 *   <li>DELETE option — removes it; group still exists.
 *   <li>DELETE group — removes the group and all its options.
 *   <li>DELETE/PATCH of unknown ids → 404 (NoSuchElementException).
 *   <li>Cross-tenant PATCH/DELETE of another tenant's group/option → 404 (RLS).
 *   <li>Historical order snapshot is intact after option hard-delete (snapshot verified via admin
 *       connection).
 * </ol>
 *
 * <p>Extends {@link PostgresRlsTestBase} — uses a real PostgreSQL 16 container with {@code
 * app_user} and FORCE ROW LEVEL SECURITY.
 */
@SpringBootTest
class ModifierEditDeleteTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "manager@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private ModifierService modifierService;

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private UUID createItem(String tenant) throws Exception {
    return TenantContext.callAs(
        tenant,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS_ID, "Test Item", "MAIN", 10_000L, "IDR"))
                .id());
  }

  private UUID createGroup(String tenant, UUID itemId, String name, String type, int min, int max)
      throws Exception {
    return TenantContext.callAs(
        tenant,
        ACTOR,
        () ->
            modifierService
                .createGroup(
                    itemId,
                    new CreateModifierGroupRequest(BUSINESS_ID, name, type, false, min, max, 0))
                .id());
  }

  private UUID createOption(String tenant, UUID groupId, String name, long price) throws Exception {
    return TenantContext.callAs(
        tenant,
        ACTOR,
        () ->
            modifierService
                .createOption(groupId, new CreateModifierOptionRequest(BUSINESS_ID, name, price, 0))
                .id());
  }

  // -------------------------------------------------------------------------
  // PATCH group — rename
  // -------------------------------------------------------------------------

  @Test
  void patchGroupRenamesGroup() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Size", "SINGLE", 0, 1);

    ModifierGroupResponse updated =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService.updateGroup(
                    groupId,
                    new UpdateModifierGroupRequest("Ukuran", null, null, null, null, null)));

    assertThat(updated.id()).isEqualTo(groupId);
    assertThat(updated.name()).isEqualTo("Ukuran");
    // Unchanged fields.
    assertThat(updated.selectionType()).isEqualTo("SINGLE");
    assertThat(updated.minSelect()).isEqualTo(0);
    assertThat(updated.maxSelect()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // PATCH group — change selectionType + min/max
  // -------------------------------------------------------------------------

  @Test
  void patchGroupChangesTypeAndMinMax() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Toppings", "SINGLE", 0, 1);

    ModifierGroupResponse updated =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService.updateGroup(
                    groupId, new UpdateModifierGroupRequest(null, "MULTI", true, 1, 3, null)));

    assertThat(updated.selectionType()).isEqualTo("MULTI");
    assertThat(updated.required()).isTrue();
    assertThat(updated.minSelect()).isEqualTo(1);
    assertThat(updated.maxSelect()).isEqualTo(3);
    // Name unchanged.
    assertThat(updated.name()).isEqualTo("Toppings");
  }

  // -------------------------------------------------------------------------
  // PATCH group — rejects invalid min/max
  // -------------------------------------------------------------------------

  @Test
  void patchGroupRejectsMinGreaterThanMax() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Extras", "MULTI", 0, 2);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        modifierService.updateGroup(
                            groupId,
                            new UpdateModifierGroupRequest(null, null, null, 5, 2, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxSelect");
  }

  // -------------------------------------------------------------------------
  // PATCH group — rejects SINGLE with maxSelect > 1
  // -------------------------------------------------------------------------

  @Test
  void patchGroupRejectsSingleTypeWithMaxSelectGreaterThanOne() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Sauce", "MULTI", 0, 3);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        modifierService.updateGroup(
                            groupId,
                            new UpdateModifierGroupRequest(null, "SINGLE", null, null, 3, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SINGLE");
  }

  // -------------------------------------------------------------------------
  // PATCH option — changes name and priceDeltaMinor
  // -------------------------------------------------------------------------

  @Test
  void patchOptionChangesNameAndPrice() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Size", "SINGLE", 0, 1);
    UUID optionId = createOption(TENANT_A, groupId, "Large", 5_000L);

    ModifierOptionResponse updated =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService.updateOption(
                    optionId, new UpdateModifierOptionRequest("Extra Large", 8_000L, null)));

    assertThat(updated.id()).isEqualTo(optionId);
    assertThat(updated.name()).isEqualTo("Extra Large");
    assertThat(updated.priceDeltaMinor()).isEqualTo(8_000L);
    // displayOrder unchanged.
    assertThat(updated.displayOrder()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // PATCH option — changes displayOrder only
  // -------------------------------------------------------------------------

  @Test
  void patchOptionChangesDisplayOrderOnly() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Spice", "SINGLE", 0, 1);
    UUID optionId = createOption(TENANT_A, groupId, "Mild", 0L);

    ModifierOptionResponse updated =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService.updateOption(
                    optionId, new UpdateModifierOptionRequest(null, null, 5)));

    assertThat(updated.displayOrder()).isEqualTo(5);
    assertThat(updated.name()).isEqualTo("Mild");
    assertThat(updated.priceDeltaMinor()).isEqualTo(0L);
  }

  // -------------------------------------------------------------------------
  // DELETE option — removes it; group still exists
  // -------------------------------------------------------------------------

  @Test
  void deleteOptionRemovesItFromGroup() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Size", "MULTI", 0, 2);
    UUID smallId = createOption(TENANT_A, groupId, "Small", 0L);
    UUID largeId = createOption(TENANT_A, groupId, "Large", 5_000L);

    // Delete "Large".
    TenantContext.runAs(TENANT_A, ACTOR, () -> modifierService.deleteOption(largeId));

    // The group still exists; only "Small" remains.
    List<ModifierGroupResponse> groups =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> modifierService.findGroupsWithAllOptions(itemId));

    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).id()).isEqualTo(groupId);
    List<ModifierOptionResponse> options = groups.get(0).options();
    assertThat(options).hasSize(1);
    assertThat(options.get(0).id()).isEqualTo(smallId);
    assertThat(options.get(0).name()).isEqualTo("Small");
  }

  // -------------------------------------------------------------------------
  // DELETE group — removes group and all its options
  // -------------------------------------------------------------------------

  @Test
  void deleteGroupRemovesGroupAndAllItsOptions() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Extras", "MULTI", 0, 3);
    createOption(TENANT_A, groupId, "Cheese", 2_000L);
    createOption(TENANT_A, groupId, "Bacon", 3_000L);

    // Delete the group.
    TenantContext.runAs(TENANT_A, ACTOR, () -> modifierService.deleteGroup(groupId));

    // No groups remain for the item.
    List<ModifierGroupResponse> groups =
        TenantContext.callAs(
            TENANT_A, ACTOR, () -> modifierService.findGroupsWithAllOptions(itemId));
    assertThat(groups).isEmpty();

    // Verify via admin (BYPASSRLS) that the option rows are also gone.
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM menu_item_modifier_option WHERE group_id = '" + groupId + "'");
      rs.next();
      assertThat(rs.getLong(1))
          .as("all options for the deleted group must be hard-deleted")
          .isEqualTo(0L);
    }
  }

  // -------------------------------------------------------------------------
  // DELETE/PATCH of unknown ids → 404 (NoSuchElementException)
  // -------------------------------------------------------------------------

  @Test
  void patchUnknownGroupThrowsNotFound() throws Exception {
    UUID unknown = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        modifierService.updateGroup(
                            unknown,
                            new UpdateModifierGroupRequest("X", null, null, null, null, null))))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining(unknown.toString());
  }

  @Test
  void deleteUnknownGroupThrowsNotFound() {
    UUID unknown = UUID.randomUUID();
    assertThatThrownBy(
            () -> TenantContext.runAs(TENANT_A, ACTOR, () -> modifierService.deleteGroup(unknown)))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining(unknown.toString());
  }

  @Test
  void patchUnknownOptionThrowsNotFound() throws Exception {
    UUID unknown = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        modifierService.updateOption(
                            unknown, new UpdateModifierOptionRequest("Y", null, null))))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining(unknown.toString());
  }

  @Test
  void deleteUnknownOptionThrowsNotFound() {
    UUID unknown = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    TENANT_A, ACTOR, () -> modifierService.deleteOption(unknown)))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining(unknown.toString());
  }

  // -------------------------------------------------------------------------
  // Cross-tenant PATCH/DELETE → 404 (RLS makes group/option invisible)
  // -------------------------------------------------------------------------

  @Test
  void crossTenantPatchGroupIsBlockedByRls() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Size", "SINGLE", 0, 1);

    // TENANT_B attempts to rename TENANT_A's group → RLS hides the row → 404.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR,
                    () ->
                        modifierService.updateGroup(
                            groupId,
                            new UpdateModifierGroupRequest(
                                "Stolen", null, null, null, null, null))))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void crossTenantDeleteGroupIsBlockedByRls() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Size", "SINGLE", 0, 1);

    // TENANT_B attempts to delete TENANT_A's group → RLS hides the row → 404.
    assertThatThrownBy(
            () -> TenantContext.runAs(TENANT_B, ACTOR, () -> modifierService.deleteGroup(groupId)))
        .isInstanceOf(NoSuchElementException.class);

    // Confirm row still exists via admin connection.
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM menu_item_modifier_group WHERE id = '" + groupId + "'");
      rs.next();
      assertThat(rs.getLong(1))
          .as("group still exists in DB — was not deleted by cross-tenant attempt")
          .isEqualTo(1L);
    }
  }

  @Test
  void crossTenantPatchOptionIsBlockedByRls() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Spice", "SINGLE", 0, 1);
    UUID optionId = createOption(TENANT_A, groupId, "Hot", 0L);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR,
                    () ->
                        modifierService.updateOption(
                            optionId, new UpdateModifierOptionRequest("Stolen", null, null))))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void crossTenantDeleteOptionIsBlockedByRls() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Spice", "SINGLE", 0, 1);
    UUID optionId = createOption(TENANT_A, groupId, "Hot", 0L);

    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    TENANT_B, ACTOR, () -> modifierService.deleteOption(optionId)))
        .isInstanceOf(NoSuchElementException.class);

    // Option still exists.
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM menu_item_modifier_option WHERE id = '" + optionId + "'");
      rs.next();
      assertThat(rs.getLong(1))
          .as("option still exists in DB — was not deleted by cross-tenant attempt")
          .isEqualTo(1L);
    }
  }

  // -------------------------------------------------------------------------
  // Historical snapshot is intact after hard-delete (snapshot not FK)
  // -------------------------------------------------------------------------

  @Test
  void historicalSnapshotInOrderLineModifierIntactAfterOptionDelete() throws Exception {
    UUID itemId = createItem(TENANT_A);
    UUID groupId = createGroup(TENANT_A, itemId, "Size", "SINGLE", 0, 1);
    UUID optionId = createOption(TENANT_A, groupId, "Large", 5_000L);

    // Write a fake order_line_modifier snapshot directly via admin (bypassing the order flow)
    // to simulate a historical order that referenced this option.
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      // We need an order_line_id to satisfy the FK — create a minimal chain.
      UUID orderId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();
      UUID modifierRowId = UUID.randomUUID();

      // Insert a restaurant_order row (all NOT NULL columns required).
      st.execute(
          "INSERT INTO restaurant_order"
              + " (id, business_id, currency, total_minor, status, order_type,"
              + "  occurred_at, idempotency_key,"
              + "  created_at, created_by, updated_at, updated_by, version, company_id)"
              + " VALUES ('"
              + orderId
              + "', '"
              + BUSINESS_ID
              + "', 'IDR', 15000, 'COMPLETED', 'DINE_IN',"
              + "  now(), '"
              + modifierRowId
              + "',"
              + "  now(), 'test', now(), 'test', 0, '"
              + TENANT_A
              + "')");

      // Insert an order_line row.
      st.execute(
          "INSERT INTO order_line"
              + " (id, order_id, menu_item_id, name_snapshot, unit_price_minor, qty,"
              + "  line_total_minor, created_at, created_by, updated_at, updated_by, version,"
              + "  company_id)"
              + " VALUES ('"
              + lineId
              + "', '"
              + orderId
              + "', '"
              + itemId
              + "', 'Test Item', 10000, 1, 15000,"
              + "  now(), 'test', now(), 'test', 0, '"
              + TENANT_A
              + "')");

      // Insert the snapshot row — option_id stored but NO FK constraint.
      st.execute(
          "INSERT INTO order_line_modifier"
              + " (id, order_line_id, option_id, name_snapshot, price_delta_minor,"
              + "  created_at, created_by, updated_at, updated_by, version, company_id)"
              + " VALUES ('"
              + modifierRowId
              + "', '"
              + lineId
              + "', '"
              + optionId
              + "', 'Large', 5000,"
              + "  now(), 'test', now(), 'test', 0, '"
              + TENANT_A
              + "')");
    }

    // Now hard-delete the option from the catalog.
    TenantContext.runAs(TENANT_A, ACTOR, () -> modifierService.deleteOption(optionId));

    // Verify: the option row is gone from the catalog.
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      ResultSet catalogRs =
          st.executeQuery(
              "SELECT count(*) FROM menu_item_modifier_option WHERE id = '" + optionId + "'");
      catalogRs.next();
      assertThat(catalogRs.getLong(1)).as("catalog option row must be deleted").isEqualTo(0L);

      // But the snapshot in order_line_modifier must still exist with the correct values.
      ResultSet snapshotRs =
          st.executeQuery(
              "SELECT name_snapshot, price_delta_minor FROM order_line_modifier"
                  + " WHERE option_id = '"
                  + optionId
                  + "'");
      assertThat(snapshotRs.next())
          .as("snapshot row must still exist after option delete")
          .isTrue();
      assertThat(snapshotRs.getString("name_snapshot"))
          .as("snapshot name must be intact")
          .isEqualTo("Large");
      assertThat(snapshotRs.getLong("price_delta_minor"))
          .as("snapshot price must be intact")
          .isEqualTo(5_000L);
    }
  }
}
