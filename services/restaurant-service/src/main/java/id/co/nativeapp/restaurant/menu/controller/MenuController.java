package id.co.nativeapp.restaurant.menu.controller;

import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.MigrateMenuImagesResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/menu} and {@code POST /api/v1/menu} — menu item management.
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request edge by {@link
 * id.co.nativeapp.restaurant.config.DevTenantFilter DevTenantFilter}), never from the body (rule
 * 5).
 *
 * <ul>
 *   <li>{@code GET /api/v1/menu?businessId={uuid}} — active items for the bound tenant and
 *       business; returns {@code 200} with a JSON array.
 *   <li>{@code POST /api/v1/menu} — creates a new active menu item; returns {@code 201 Created}
 *       with a {@code Location} header.
 * </ul>
 */
@Tag(name = "Menu", description = "Menu item catalogue management and cashier read")
@RestController
@RequestMapping("/api/v1/menu")
public class MenuController {

  private final MenuService menuService;

  public MenuController(MenuService menuService) {
    this.menuService = menuService;
  }

  /**
   * Returns active items for the given business, scoped by the bound tenant (RLS). No explicit
   * {@code company_id} filter; the RLS policy on {@code menu_item} restricts results to the current
   * company.
   */
  @Operation(
      summary = "List active menu items for a business",
      description =
          "Returns active items for the given business, scoped by the bound tenant (RLS). No"
              + " explicit company_id filter; the RLS policy on menu_item restricts results to the"
              + " current company.")
  @GetMapping
  public ResponseEntity<List<MenuItemResponse>> getActiveMenu(@RequestParam UUID businessId) {
    return ResponseEntity.ok(menuService.findActiveByBusiness(businessId));
  }

  /**
   * Creates a new active menu item. {@code 201 Created} + {@code Location} header on success.
   * Bean-validation on the body rejects missing/invalid fields with a {@code 400 ProblemDetail}.
   */
  @Operation(
      summary = "Create a menu item",
      description =
          "Creates a new active menu item. Returns 201 Created with a Location header on success."
              + " Bean-validation on the body rejects missing/invalid fields with a 400"
              + " ProblemDetail.")
  @PostMapping
  public ResponseEntity<MenuItemResponse> createMenuItem(
      @Valid @RequestBody CreateMenuItemRequest request) {
    MenuItemResponse created = menuService.createItem(request);
    return ResponseEntity.created(URI.create("/api/v1/menu/" + created.id())).body(created);
  }

  /**
   * Soft-deletes a menu item (sets {@code active = false}). The item immediately disappears from
   * {@code GET /api/v1/menu} but existing orders/sales that reference it are unaffected. Scoped to
   * the bound tenant (RLS) — an item belonging to another company is invisible and returns {@code
   * 404}.
   *
   * @param itemId the menu item id
   * @return {@code 204 No Content} on success; {@code 404} if not found or not visible
   */
  @Operation(
      summary = "Delete (soft-delete) a menu item",
      description =
          "Soft-deletes a menu item by setting active=false. The item disappears from GET /api/v1/menu"
              + " but historical order/sale references are unaffected. Returns 204 No Content on"
              + " success, 404 if the item is not found or not visible to the bound tenant.")
  @DeleteMapping("/{itemId}")
  public ResponseEntity<Void> deleteMenuItem(@PathVariable UUID itemId) {
    menuService.deleteItem(itemId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Partially updates a menu item. Only the fields supplied in the request body are applied; absent
   * (null) fields are left unchanged. The item's currency cannot be changed — {@code priceMinor}
   * updates the price amount only.
   *
   * <p>imageUrl convention: {@code null} or omitted from JSON = leave unchanged; empty string
   * ({@code ""}) = clear the image; any other value = set/replace the image.
   *
   * @param itemId the menu item id
   * @param request partial update body (all fields optional)
   * @return {@code 200 OK} with the updated {@link MenuItemResponse}; {@code 404} if not found or
   *     not visible to the bound tenant; {@code 400} if validation fails (e.g. imageUrl &gt; 3MB or
   *     priceMinor &le; 0)
   */
  @Operation(
      summary = "Partially update a menu item",
      description =
          "Applies a partial update to a menu item — only non-null fields are changed. The item's"
              + " currency cannot be changed; priceMinor updates the price amount only. For imageUrl:"
              + " null/absent = leave unchanged, empty string = clear the image, any other value ="
              + " set/replace. Returns 200 OK with the updated item, 404 if not found or not visible"
              + " to the bound tenant, 400 on validation failure.")
  @PatchMapping("/{itemId}")
  public ResponseEntity<MenuItemResponse> patchMenuItem(
      @PathVariable UUID itemId, @Valid @RequestBody UpdateMenuItemRequest request) {
    return ResponseEntity.ok(menuService.updateItem(itemId, request));
  }

  /**
   * Converts the bound tenant's remaining legacy inline base64 menu images to the object store (ADR
   * 0048). Owner-only at the gateway; idempotent — a re-run on a fully migrated tenant returns
   * {@code {"migrated":0,"skipped":0}}. Rows whose legacy payload no longer validates are counted
   * as {@code skipped} and keep rendering via dual-read.
   */
  @Operation(
      summary = "Migrate legacy inline menu images to the object store",
      description =
          "Converts every remaining base64-data-URL menu image of the bound tenant into the object"
              + " store (ADR 0048), replacing the inline payload with a content-addressed key."
              + " Idempotent and RLS-scoped; owner-only at the gateway. Returns the migrated and"
              + " skipped counts — skipped rows keep serving via dual-read.")
  @PostMapping("/images/migrate")
  public ResponseEntity<MigrateMenuImagesResponse> migrateImages() {
    return ResponseEntity.ok(menuService.migrateImages());
  }
}
