package id.co.nativeapp.restaurant.inventory.dto;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One counted line on an {@link IngredientStocktakeResponse}. {@code unitCostMinor} / {@code
 * varianceValueMinor} are {@code null}/{@code 0} respectively when the ingredient carries no unit
 * cost (the count still adjusted stock, but the variance has no ledger value).
 */
public record IngredientStocktakeLineResponse(
    UUID ingredientId,
    String name,
    String unit,
    int systemQty,
    int countedQty,
    int varianceQty,
    @Nullable Long unitCostMinor,
    long varianceValueMinor) {}
