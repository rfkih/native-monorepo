package id.co.nativeapp.restaurant.menu.dto;

import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Response body for a single menu item.
 *
 * <p>Phase 3 additions: {@code categoryId} (the linked {@code MenuCategory} UUID — null for legacy
 * items not yet assigned to a category), {@code available} (86 flag), and {@code modifierGroups}.
 *
 * <p>Stock increment: {@code stockQuantity} ({@code null} = infinite / untracked; &ge; 0 =
 * tracked). The frontend computes effective sold-out from {@code available &amp;&amp;
 * (stockQuantity == null || stockQuantity > 0)}.
 *
 * <p>{@code modifierGroups} is populated on the cashier read path ({@code GET /api/v1/menu}) via a
 * batch load in {@link id.co.nativeapp.restaurant.menu.service.MenuReader#findActiveByBusiness
 * MenuReader.findActiveByBusiness}: active groups and their available options are embedded directly
 * in the list response so the POS modifier picker can open without a second per-item request.
 * Inactive groups and unavailable (86'd) options are excluded. The write-path factory {@link
 * #from(id.co.nativeapp.restaurant.menu.domain.MenuItem)} always returns an empty list.
 *
 * <p>Image (ADR 0048): {@code imageUrl} is the public object-store URL ({@code /api/media/…}) for
 * migrated images, or the legacy inline data URL / external HTTP(S) URL for not-yet-migrated rows;
 * {@code null} when no image has been set. Public URLs keep the original property's contract — an
 * {@code <img>} cannot send a bearer token, so the serving path is the anonymous content-hash-keyed
 * media proxy, never an authenticated image endpoint.
 *
 * <p>{@code unitCostMinor} (ADR 0038 phase 3): the item's unit cost in minor units (same currency
 * as {@code priceMinor}); {@code null} when no cost has been recorded.
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
    @Nullable Integer stockQuantity,
    @Nullable String imageUrl,
    @Nullable Long unitCostMinor,
    List<ModifierGroupResponse> modifierGroups) {

  /**
   * Maps the write-path aggregate to the response shape (no modifier groups). {@code
   * resolvedImageUrl} is the ALREADY-RESOLVED public/legacy image URL (the service layer resolves
   * an object key via {@code MenuImageStore#resolve} — this DTO cannot, and must not, reach the
   * media configuration itself).
   */
  public static MenuItemResponse from(MenuItem item, @Nullable String resolvedImageUrl) {
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
        item.getStockQuantity(),
        resolvedImageUrl,
        item.getUnitCostMinor(),
        List.of());
  }
}
