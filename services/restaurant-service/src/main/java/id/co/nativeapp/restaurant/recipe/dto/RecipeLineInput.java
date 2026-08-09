package id.co.nativeapp.restaurant.recipe.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One line of a {@link PutRecipeRequest} (ADR 0050 phase A). {@code modifierOptionId == null} = a
 * base line (qty must be &gt; 0); non-null = a signed delta for that option (qty nonzero, may be
 * negative). Sign rules are enforced in the domain constructor + the V34 CHECK — bean validation
 * here only pins the non-null shape.
 */
public record RecipeLineInput(
    @NotNull UUID ingredientId, @Nullable UUID modifierOptionId, @NotNull Integer qtyPerPortion) {}
