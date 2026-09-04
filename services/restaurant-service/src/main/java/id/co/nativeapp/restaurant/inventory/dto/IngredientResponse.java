package id.co.nativeapp.restaurant.inventory.dto;

import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Response body for a single ingredient (ADR 0046 phase 1).
 *
 * <p>{@code stockQty} is ALWAYS a number (never {@code null}) — unlike {@code
 * MenuItemResponse.stockQuantity}, an ingredient has no untracked state. {@code unitCostMinor}/
 * {@code costCurrency} are {@code null} together when the ingredient carries no cost; {@code
 * unitCostMinor} is the DERIVED moving-average unit cost ({@code round(stockValueMinor/stockQty)},
 * V36), and {@code stockValueMinor} is the total value of stock on hand (0 when uncosted or empty).
 */
public record IngredientResponse(
    UUID id,
    UUID businessId,
    String name,
    String unit,
    @Nullable String displayUnit,
    int stockQty,
    @Nullable Long unitCostMinor,
    @Nullable String costCurrency,
    long stockValueMinor,
    boolean active,
    @Nullable Integer packSize) {

  /** Pre-pack-size shape — every existing caller and test keeps compiling unchanged. */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public IngredientResponse(
      UUID id,
      UUID businessId,
      String name,
      String unit,
      @Nullable String displayUnit,
      int stockQty,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency,
      long stockValueMinor,
      boolean active) {
    this(
        id,
        businessId,
        name,
        unit,
        displayUnit,
        stockQty,
        unitCostMinor,
        costCurrency,
        stockValueMinor,
        active,
        null);
  }

  /** Maps the write-path aggregate to the response shape. */
  public static IngredientResponse from(Ingredient ingredient) {
    return new IngredientResponse(
        ingredient.getId(),
        ingredient.getBusinessId(),
        ingredient.getName(),
        ingredient.getUnit(),
        ingredient.getDisplayUnit(),
        ingredient.getStockQty(),
        ingredient.getUnitCostMinor(),
        ingredient.getCostCurrency(),
        ingredient.getStockValueMinor(),
        ingredient.isActive(),
        ingredient.getPackSize());
  }
}
