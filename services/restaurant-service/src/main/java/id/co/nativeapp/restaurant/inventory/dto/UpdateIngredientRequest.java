package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code PATCH /api/v1/ingredients/{id}} (ADR 0046 phase 1).
 *
 * <p>All fields are optional — only non-null fields are applied to the ingredient (partial update).
 *
 * <p>{@code unitCostMinor}: {@code null} = leave the cost unchanged; a non-null value (&ge; 0) sets
 * the ingredient's cost and REQUIRES {@code costCurrency} in the same request (both-or-neither,
 * enforced by {@code Ingredient#setUnitCost}). There is no "clear the cost" affordance on this
 * endpoint (mirrors {@code UpdateMenuItemRequest}).
 */
public record UpdateIngredientRequest(
    @Nullable @Size(max = 255) String name,
    @Nullable @Size(max = 16) String unit,
    @Nullable @Size(max = 16) String displayUnit,
    @Nullable @PositiveOrZero Long unitCostMinor,
    @Nullable @Size(min = 3, max = 3) String costCurrency,
    @Nullable @Positive Integer packSize,
    // BOXED, not primitive: Jackson's record deserializer treats a MISSING primitive creator
    // property as an error, so a PATCH body that omits this would 400 (the CreateBillRequest
    // note). Null means "the field was not sent" = leave the pack size alone.
    @Nullable Boolean clearPackSize) {

  /** Pre-pack-size callers (and every existing test) keep compiling unchanged. */
  public UpdateIngredientRequest(
      @Nullable String name,
      @Nullable String unit,
      @Nullable String displayUnit,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency) {
    this(name, unit, displayUnit, unitCostMinor, costCurrency, null, null);
  }
}
