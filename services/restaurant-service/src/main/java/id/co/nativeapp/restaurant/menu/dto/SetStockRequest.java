package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code PUT /api/v1/menu/{itemId}/stock}.
 *
 * <p>{@code quantity} is intentionally {@code @Nullable}: pass {@code null} to switch the item to
 * infinite / untracked; pass a value &ge; 0 to set an absolute tracked stock level.
 */
public record SetStockRequest(@Nullable @Min(0) Integer quantity) {}
