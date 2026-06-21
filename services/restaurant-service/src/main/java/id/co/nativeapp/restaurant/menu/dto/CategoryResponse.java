package id.co.nativeapp.restaurant.menu.dto;

import id.co.nativeapp.restaurant.menu.domain.MenuCategory;
import java.util.UUID;

/**
 * Response body for a single menu category.
 *
 * @param id the category id
 * @param businessId the owning business unit
 * @param name the display name
 * @param displayOrder the sort position
 * @param active whether the category is active (visible in cashier view)
 */
public record CategoryResponse(
    UUID id, UUID businessId, String name, int displayOrder, boolean active) {

  /** Maps from the write-path aggregate. */
  public static CategoryResponse from(MenuCategory category) {
    return new CategoryResponse(
        category.getId(),
        category.getBusinessId(),
        category.getName(),
        category.getDisplayOrder(),
        category.isActive());
  }
  // NOTE: from(MenuCategoryView) lives in CategoryReader (service layer) — not here —
  // because the ArchUnit rule prohibits dto from depending on projection.
}
