package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * One counted line in a {@link SubmitIngredientStocktakeRequest} — the cashier/manager's physical
 * count for one ingredient. {@code company_id} is intentionally absent (rule 5); the ingredient's
 * outlet membership and active state are validated server-side by {@code
 * IngredientStocktakeWriter}.
 */
public record IngredientStocktakeLineInput(
    @NotNull UUID ingredientId, @NotNull @PositiveOrZero Integer countedQty) {}
