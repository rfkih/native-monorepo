package id.co.nativeapp.restaurant.inventory.dto;

import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;

/**
 * Outcome of {@link IngredientStocktakeService#submit}.
 *
 * @param stocktake the submitted (or pre-existing, on replay) ingredient stocktake
 * @param created {@code true} if this call inserted a new stocktake, adjusted stock, and (when at
 *     least one costed line was present) emitted {@code StocktakeCompleted}; {@code false} if an
 *     existing stocktake with the same {@code (company_id, idempotency_key)} was returned and NO
 *     second adjustment/event occurred
 */
public record SubmitIngredientStocktakeResult(
    IngredientStocktakeResponse stocktake, boolean created) {}
