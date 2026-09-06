package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/ingredients/{id}/convert-unit} — re-express an ingredient in
 * a finer base unit without changing the physical stock it represents.
 *
 * <p>{@code company_id} is intentionally absent (rule 5); the ingredient's tenant comes from the
 * bound scope via RLS.
 *
 * @param toUnit the new base unit ({@code g}, {@code ml}, {@code pcs})
 * @param toDisplayUnit the label to show above it ({@code kg}, {@code liter}), or null for none
 * @param factor how many NEW base units one OLD base unit is worth — "1 pack = 1000 g" is 1000.
 *     Positive and whole: a conversion that needed a fraction would be going the wrong way (to a
 *     COARSER unit), which loses the precision this operation exists to gain
 */
public record ConvertIngredientUnitRequest(
    @NotBlank @Size(max = 16) String toUnit,
    @Nullable @Size(max = 16) String toDisplayUnit,
    @NotNull @Positive Integer factor) {}
