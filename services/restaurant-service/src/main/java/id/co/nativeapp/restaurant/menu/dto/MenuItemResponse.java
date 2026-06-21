package id.co.nativeapp.restaurant.menu.dto;

import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import java.util.List;
import java.util.UUID;

/**
 * Response body for a single menu item.
 *
 * <p>Phase 3 additions: {@code categoryId} (the linked {@code MenuCategory} UUID — null for legacy
 * items not yet assigned to a category), {@code available} (86 flag), and {@code modifierGroups}.
 *
 * <p>{@code modifierGroups} is populated on the cashier read path ({@code GET /api/v1/menu}) via a
 * batch load in {@link id.co.nativeapp.restaurant.menu.service.MenuReader#findActiveByBusiness
 * MenuReader.findActiveByBusiness}: active groups and their available options are embedded directly
 * in the list response so the POS modifier picker can open without a second per-item request.
 * Inactive groups and unavailable (86'd) options are excluded. The write-path factory {@link
 * #from(id.co.nativeapp.restaurant.menu.domain.MenuItem)} always returns an empty list.
 */
public record MenuItemResponse(
    UUID id,
    UUID businessId,
    String name,
    String category,
    UUID categoryId,
    long priceMinor,
    String currency,
    boolean active,
    boolean available,
    List<ModifierGroupResponse> modifierGroups) {

  /** Maps the write-path aggregate to the response shape (no modifier groups). */
  public static MenuItemResponse from(MenuItem item) {
    return new MenuItemResponse(
        item.getId(),
        item.getBusinessId(),
        item.getName(),
        item.getCategory(),
        item.getCategoryId(),
        item.getPrice().amountMinor(),
        item.getPrice().currency().getCurrencyCode(),
        item.isActive(),
        item.isAvailable(),
        List.of());
  }
}
