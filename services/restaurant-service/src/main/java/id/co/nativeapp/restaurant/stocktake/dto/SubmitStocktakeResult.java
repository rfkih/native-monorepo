package id.co.nativeapp.restaurant.stocktake.dto;

import id.co.nativeapp.restaurant.stocktake.service.StocktakeService;

/**
 * Outcome of {@link StocktakeService#submit}.
 *
 * @param stocktake the submitted (or pre-existing, on replay) stocktake
 * @param created {@code true} if this call inserted a new stocktake, adjusted stock, and emitted
 *     {@code StocktakeCompleted}; {@code false} if an existing stocktake with the same {@code
 *     (company_id, idempotency_key)} was returned and NO second adjustment/event occurred
 */
public record SubmitStocktakeResult(StocktakeResponse stocktake, boolean created) {}
