package id.co.nativeapp.restaurant.integrity.dto;

import jakarta.annotation.Nullable;

/**
 * What the report could NOT see — published alongside the estimate, not buried under it.
 *
 * <p>Stock-based signals only speak when somebody counts, and ingredient signals only reach menu
 * items that have a recipe. Without these figures a reassuring total is indistinguishable from a
 * total computed over almost no evidence, and the second would be read as the first.
 *
 * @param totalSoldQty units sold in the window across every menu item
 * @param recipeBackedSoldQty of those, the units whose item has a recipe — the rest are invisible
 *     to ingredient-shortfall detection
 * @param daysSinceIngredientCount days since the last ingredient opname, or {@code null} if there
 *     has never been one (never 0 — "never counted" is not "counted today")
 * @param daysSinceItemCount days since the last menu-item stocktake, or {@code null} if never
 * @param manualStockCorrections how many separate hand-corrections were made to stock in the window
 *     — context for the estimate, and a signal in its own right when it is high
 */
public record LeakCoverageResponse(
    long totalSoldQty,
    long recipeBackedSoldQty,
    @Nullable Long daysSinceIngredientCount,
    @Nullable Long daysSinceItemCount,
    long manualStockCorrections) {}
