package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/menu/{itemId}/stock/add}.
 *
 * <p>{@code amount} is the signed delta to add to the current tracked stock. Positive values
 * restock the item; negative values manually reduce stock (floored at 0 — cannot go negative). The
 * item must already be tracked (stock_quantity IS NOT NULL); otherwise a {@code 422} is returned.
 */
public record AddStockRequest(@NotNull Integer amount) {}
