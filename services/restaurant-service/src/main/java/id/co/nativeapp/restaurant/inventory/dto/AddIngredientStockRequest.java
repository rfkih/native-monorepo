package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/ingredients/{id}/stock/add}.
 *
 * <p>{@code amount} is the signed delta to add to the current stock. Positive values receive stock;
 * negative values manually reduce stock (floored at 0 — cannot go negative).
 */
public record AddIngredientStockRequest(@NotNull Integer amount) {}
