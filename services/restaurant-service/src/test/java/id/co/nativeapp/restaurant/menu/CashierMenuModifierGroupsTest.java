package id.co.nativeapp.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierGroupRequest;
import id.co.nativeapp.restaurant.menu.dto.CreateModifierOptionRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierGroupResponse;
import id.co.nativeapp.restaurant.menu.dto.ModifierOptionResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.menu.service.ModifierService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that the cashier menu-list read ({@code GET /api/v1/menu?businessId=}) embeds modifier
 * groups and their available options directly in each {@link MenuItemResponse} — fixing the POS
 * contract mismatch where the Phase 3 console reads {@code item.modifierGroups} from the list to
 * decide whether to open the modifier picker.
 *
 * <p>Covers:
 *
 * <ol>
 *   <li>Item with one active modifier group and one available option: group + option are embedded.
 *   <li>Item with no modifier groups: {@code modifierGroups} is an empty list (not null).
 *   <li>Unavailable (86'd) option is excluded from the embedded list.
 *   <li>Tenancy isolation: tenant B's menu-list read shows none of tenant A's groups.
 * </ol>
 *
 * <p>Extends {@link PostgresRlsTestBase} — uses a real PostgreSQL 16 container with the app_user
 * role and FORCE ROW LEVEL SECURITY, so RLS isolation is real, not mocked.
 */
@SpringBootTest
class CashierMenuModifierGroupsTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MenuService menuService;
  @Autowired private ModifierService modifierService;

  /**
   * An item with one active modifier group ("Size") and one available option ("Large", +5 000) must
   * appear in the cashier menu-list with the group and option embedded. No second HTTP call should
   * be needed.
   */
  @Test
  void itemWithModifierGroupEmbeddsGroupAndOptionInCashierList() throws Exception {
    // Create menu item.
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_ID, "Nasi Goreng", "MAIN", 15_000L, "IDR"))
                    .id());

    // Create modifier group: "Size", SINGLE, optional.
    UUID groupId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createGroup(
                        itemId,
                        new CreateModifierGroupRequest(
                            BUSINESS_ID, "Size", "SINGLE", false, 0, 1, 0))
                    .id());

    // Add one available option.
    UUID largeOptionId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(
                        groupId, new CreateModifierOptionRequest(BUSINESS_ID, "Large", 5_000L, 0))
                    .id());

    // Cashier list read.
    List<MenuItemResponse> menu =
        TenantContext.callAs(TENANT_A, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS_ID));

    assertThat(menu).hasSize(1);
    MenuItemResponse item = menu.get(0);
    assertThat(item.id()).isEqualTo(itemId);

    // The modifier group must be embedded — POS picker depends on this list.
    assertThat(item.modifierGroups()).hasSize(1);
    ModifierGroupResponse group = item.modifierGroups().get(0);
    assertThat(group.id()).isEqualTo(groupId);
    assertThat(group.name()).isEqualTo("Size");
    assertThat(group.selectionType()).isEqualTo("SINGLE");
    assertThat(group.required()).isFalse();

    assertThat(group.options()).hasSize(1);
    ModifierOptionResponse option = group.options().get(0);
    assertThat(option.id()).isEqualTo(largeOptionId);
    assertThat(option.name()).isEqualTo("Large");
    assertThat(option.priceDeltaMinor()).isEqualTo(5_000L);
    assertThat(option.available()).isTrue();
  }

  /**
   * An item with no modifier groups must return an empty {@code modifierGroups} list (not null), so
   * frontend code can safely iterate without a null check.
   */
  @Test
  void itemWithNoModifierGroupsReturnsEmptyList() throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            menuService.createItem(
                new CreateMenuItemRequest(BUSINESS_ID, "Es Teh", "DRINKS", 5_000L, "IDR")));

    List<MenuItemResponse> menu =
        TenantContext.callAs(TENANT_A, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS_ID));

    assertThat(menu).hasSize(1);
    assertThat(menu.get(0).modifierGroups()).isNotNull().isEmpty();
  }

  /**
   * An option that has been 86'd ({@code available = false}) must NOT appear in the embedded
   * options list on the cashier read path, consistent with how {@code GET
   * /api/v1/menu/{id}/modifier-groups} (non-admin) filters it.
   */
  @Test
  void unavailableOptionIsExcludedFromCashierList() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS_ID, "Mie Goreng", "MAIN", 12_000L, "IDR"))
                    .id());

    UUID groupId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createGroup(
                        itemId,
                        new CreateModifierGroupRequest(
                            BUSINESS_ID, "Spice", "SINGLE", false, 0, 1, 0))
                    .id());

    // Add two options; 86 one of them.
    UUID mildId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(
                        groupId, new CreateModifierOptionRequest(BUSINESS_ID, "Mild", 0L, 0))
                    .id());

    UUID hotId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                modifierService
                    .createOption(
                        groupId, new CreateModifierOptionRequest(BUSINESS_ID, "Hot", 0L, 1))
                    .id());

    // 86 the "Hot" option.
    TenantContext.callAs(TENANT_A, ACTOR, () -> modifierService.markOptionUnavailable(hotId));

    List<MenuItemResponse> menu =
        TenantContext.callAs(TENANT_A, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS_ID));

    assertThat(menu).hasSize(1);
    List<ModifierOptionResponse> options = menu.get(0).modifierGroups().get(0).options();

    // Only "Mild" (available) should appear; "Hot" (86'd) must be excluded.
    assertThat(options).hasSize(1);
    assertThat(options.get(0).id()).isEqualTo(mildId);
    assertThat(options.get(0).name()).isEqualTo("Mild");
  }

  /**
   * Tenancy isolation: modifier groups created under TENANT_A must not appear in TENANT_B's cashier
   * menu-list read for the same business id. RLS enforces this at the DB level.
   */
  @Test
  void tenantBMenuListShowsNoneOfTenantAGroups() throws Exception {
    // TENANT_A creates an item with a modifier group.
    UUID itemIdA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_ID, "Soto Ayam", "MAIN", 18_000L, "IDR"))
                    .id());

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            modifierService.createGroup(
                itemIdA,
                new CreateModifierGroupRequest(
                    BUSINESS_ID, "Bowl Size", "SINGLE", false, 0, 1, 0)));

    // TENANT_B reads the same businessId — should see no items (different company_id → RLS blocks).
    List<MenuItemResponse> bMenu =
        TenantContext.callAs(TENANT_B, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS_ID));

    assertThat(bMenu).isEmpty();

    // Confirm via BYPASSRLS admin connection that the row exists in the DB (it's just filtered by
    // RLS for TENANT_B).
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      java.sql.ResultSet rs =
          st.executeQuery(
              "SELECT count(*) FROM menu_item_modifier_group WHERE menu_item_id = '"
                  + itemIdA
                  + "'");
      rs.next();
      assertThat(rs.getLong(1))
          .as("group exists in DB but is RLS-invisible to tenant B")
          .isEqualTo(1L);
    }
  }

  /**
   * Multiple items in the same business list: each item embeds only its own groups, ordered by
   * display_order; no cross-contamination between items.
   */
  @Test
  void multipleItemsEachEmbedTheirOwnGroupsInDisplayOrder() throws Exception {
    // Item 1: "Burger" with two groups at different display orders.
    UUID burgerId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_ID, "Burger", "MAIN", 25_000L, "IDR"))
                    .id());

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            modifierService.createGroup(
                burgerId,
                new CreateModifierGroupRequest(BUSINESS_ID, "Sauce", "MULTI", false, 0, 2, 10)));

    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            modifierService.createGroup(
                burgerId,
                new CreateModifierGroupRequest(BUSINESS_ID, "Size", "SINGLE", false, 0, 1, 1)));

    // Item 2: "Fries" with no modifier groups.
    UUID friesId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS_ID, "Fries", "SIDES", 8_000L, "IDR"))
                    .id());

    List<MenuItemResponse> menu =
        TenantContext.callAs(TENANT_A, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS_ID));

    assertThat(menu).hasSize(2);

    MenuItemResponse burgerResp =
        menu.stream().filter(r -> r.id().equals(burgerId)).findFirst().orElseThrow();
    MenuItemResponse friesResp =
        menu.stream().filter(r -> r.id().equals(friesId)).findFirst().orElseThrow();

    // Burger: two groups, ordered by display_order (Size=1, Sauce=10).
    assertThat(burgerResp.modifierGroups()).hasSize(2);
    assertThat(burgerResp.modifierGroups().get(0).name()).isEqualTo("Size");
    assertThat(burgerResp.modifierGroups().get(1).name()).isEqualTo("Sauce");

    // Fries: no modifier groups.
    assertThat(friesResp.modifierGroups()).isEmpty();
  }
}
