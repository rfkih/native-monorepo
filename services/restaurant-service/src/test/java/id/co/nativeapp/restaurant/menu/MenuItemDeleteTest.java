package id.co.nativeapp.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for {@code DELETE /api/v1/menu/{itemId}} (soft-delete).
 *
 * <ul>
 *   <li>DELETE returns 204 (via service — the controller delegates to service which calls writer).
 *   <li>After delete the item no longer appears in {@code GET /api/v1/menu}.
 *   <li>DELETE of an unknown id throws {@link NoSuchElementException} (→ 404 via
 *       {@link id.co.nativeapp.restaurant.config.StockExceptionHandler}).
 * </ul>
 */
@SpringBootTest
class MenuItemDeleteTest extends PostgresRlsTestBase {

  private static final String TENANT = "d4d4d4d4-d4d4-d4d4-d4d4-d4d4d4d4d4d4";
  private static final String ACTOR = "admin-d4@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("e5e5e5e5-e5e5-e5e5-e5e5-e5e5e5e5e5e5");

  @Autowired private MenuService menuService;

  // ---------------------------------------------------------------------------
  // Test 1: delete an existing item — it disappears from GET /menu
  // ---------------------------------------------------------------------------

  @Test
  void deleteExistingItemRemovesItFromActiveMenu() throws Exception {
    UUID itemId = createItem("Bubur Ayam", 12_000L);

    // Before delete: item is in the active menu.
    List<MenuItemResponse> before =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    assertThat(before).extracting(MenuItemResponse::id).contains(itemId);

    // Delete.
    TenantContext.callAs(TENANT, ACTOR, () -> { menuService.deleteItem(itemId); return null; });

    // After delete: item is gone from active menu.
    List<MenuItemResponse> after =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    assertThat(after).extracting(MenuItemResponse::id).doesNotContain(itemId);
  }

  // ---------------------------------------------------------------------------
  // Test 2: delete an unknown id → NoSuchElementException (→ 404 at controller)
  // ---------------------------------------------------------------------------

  @Test
  void deleteUnknownIdThrowsNoSuchElementException() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> { menuService.deleteItem(unknown); return null; }))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining(unknown.toString());
  }

  // ---------------------------------------------------------------------------
  // Test 3: item from another tenant is invisible → same 404 (RLS isolation)
  // ---------------------------------------------------------------------------

  @Test
  void deleteItemBelongingToOtherTenantThrowsNoSuchElementException() throws Exception {
    // Create item under the main tenant.
    UUID itemId = createItem("Opor Ayam", 18_000L);

    // Try to delete it as a different tenant.
    String otherTenant = "f6f6f6f6-f6f6-f6f6-f6f6-f6f6f6f6f6f6";

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    otherTenant,
                    "hacker@other.co.id",
                    () -> { menuService.deleteItem(itemId); return null; }))
        .isInstanceOf(NoSuchElementException.class);

    // Item is still visible to the original tenant.
    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    assertThat(items).extracting(MenuItemResponse::id).contains(itemId);
  }

  // ---------------------------------------------------------------------------
  // Helper
  // ---------------------------------------------------------------------------

  private UUID createItem(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(new CreateMenuItemRequest(BUSINESS, name, "MAIN", priceMinor, "IDR"))
                .id());
  }
}
