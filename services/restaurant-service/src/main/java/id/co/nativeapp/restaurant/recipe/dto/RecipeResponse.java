package id.co.nativeapp.restaurant.recipe.dto;

import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * A menu item's recipe plus its derived HPP (ADR 0050 phase A).
 *
 * <p>{@code unitHppMinor} = Σ (base-line qty × ingredient unit cost) over the lines whose cost
 * currency matches {@code hppCurrency} — integer minor units (rule 8), no division involved. Option
 * deltas are excluded from the headline figure. {@code completeness} tells the console how
 * trustworthy the figure is:
 *
 * <ul>
 *   <li>{@code COMPLETE} — every base line carries a cost in {@code hppCurrency}
 *   <li>{@code MISSING_COST} — ≥ 1 base line has an uncosted ingredient
 *   <li>{@code CURRENCY_MISMATCH} — ≥ 1 base line is costed in a different currency (excluded)
 * </ul>
 */
public record RecipeResponse(
    UUID menuItemId,
    List<LineResponse> lines,
    @Nullable Long unitHppMinor,
    @Nullable String hppCurrency,
    Completeness completeness) {

  /** Trust level of {@link #unitHppMinor} — see class doc. */
  public enum Completeness {
    COMPLETE,
    MISSING_COST,
    CURRENCY_MISMATCH
  }

  /**
   * One recipe line with the ingredient's display fields joined for the editor. Mapping from the
   * repository projection lives in {@code RecipeReader} (a dto never touches the projection layer —
   * the ArchUnit layering rule).
   */
  public record LineResponse(
      UUID id,
      UUID ingredientId,
      String ingredientName,
      String unit,
      @Nullable UUID modifierOptionId,
      int qtyPerPortion,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency,
      boolean ingredientActive) {}
}
