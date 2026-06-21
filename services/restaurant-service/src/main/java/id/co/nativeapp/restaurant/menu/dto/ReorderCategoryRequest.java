package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;

/**
 * Request body for {@code PATCH /api/v1/menu/categories/{id}/reorder}.
 *
 * @param displayOrder the new sort position (0 = first)
 */
public record ReorderCategoryRequest(@Min(0) int displayOrder) {}
