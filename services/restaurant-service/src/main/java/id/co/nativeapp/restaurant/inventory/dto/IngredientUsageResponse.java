package id.co.nativeapp.restaurant.inventory.dto;

import java.util.UUID;

/**
 * One ingredient's quantity consumed by sales on the requested day ("terpakai", V42) — element of
 * {@code GET /api/v1/ingredients/usage}. Quantities are integer counts in the ingredient's own unit
 * (never money); ingredients with no usage that day are absent (client treats absence as 0).
 */
public record IngredientUsageResponse(UUID ingredientId, long qtyUsed) {}
