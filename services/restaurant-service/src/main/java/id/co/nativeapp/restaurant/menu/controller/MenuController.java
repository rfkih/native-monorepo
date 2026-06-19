package id.co.nativeapp.restaurant.menu.controller;

import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
  @GetMapping
  public ResponseEntity<List<MenuItemResponse>> getActiveMenu(@RequestParam UUID businessId) {
    return ResponseEntity.ok(menuService.findActiveByBusiness(businessId));
  }

  /**
   * Creates a new active menu item. {@code 201 Created} + {@code Location} header on success.
   * Bean-validation on the body rejects missing/invalid fields with a {@code 400 ProblemDetail}.
   */
  @PostMapping
  public ResponseEntity<MenuItemResponse> createMenuItem(
      @Valid @RequestBody CreateMenuItemRequest request) {
    MenuItemResponse created = menuService.createItem(request);
    return ResponseEntity.created(URI.create("/api/v1/menu/" + created.id())).body(created);
  }
}
