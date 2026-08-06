package id.co.nativeapp.restaurant.stocktake.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * One counted line in a {@link SubmitStocktakeRequest} — the cashier/manager's physical count for
 * one tracked menu item. {@code company_id} is intentionally absent (rule 5); the item's outlet
 * membership and tracked state are validated server-side by {@code StocktakeWriter}.
 */
public record StocktakeLineInput(
    @NotNull UUID menuItemId, @NotNull @PositiveOrZero Integer countedQty) {}
