package id.co.nativeapp.restaurant.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Response body for an ingredient stocktake submission or read (ADR 0046 phase 1). {@code
 * shrinkageMinor} is the SIGNED net valued shrinkage carried on {@code StocktakeCompleted} —
 * positive = net loss, negative = net gain, zero = no ledger entry. {@code currency} is {@code
 * null} when the count carried zero costed lines — nothing was posted (ADR 0046).
 */
public record IngredientStocktakeResponse(
    UUID id,
    UUID businessId,
    @Nullable String currency,
    Instant countedAt,
    long shrinkageMinor,
    List<IngredientStocktakeLineResponse> lines) {}
