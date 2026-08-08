package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/v1/ingredients/{id}/stock}.
 *
 * <p>Unlike {@code SetStockRequest} (menu items), {@code quantity} is required — an ingredient is
 * ALWAYS tracked; there is no infinite/untracked state to revert to (ADR 0046).
 */
public record SetIngredientStockRequest(@NotNull @Min(0) Integer quantity) {}
