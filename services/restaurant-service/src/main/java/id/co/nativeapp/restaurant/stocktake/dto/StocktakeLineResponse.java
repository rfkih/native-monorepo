package id.co.nativeapp.restaurant.stocktake.dto;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One counted line on a {@link StocktakeResponse}. {@code unitCostMinor} / {@code
 * varianceValueMinor} are {@code null}/{@code 0} respectively when the item carries no unit cost
 * (the count still adjusted stock, but the variance has no ledger value).
 */
public record StocktakeLineResponse(
    UUID menuItemId,
    String name,
    int systemQty,
    int countedQty,
    int varianceQty,
    @Nullable Long unitCostMinor,
    long varianceValueMinor) {}
