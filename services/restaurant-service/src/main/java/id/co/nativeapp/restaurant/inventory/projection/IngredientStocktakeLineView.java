package id.co.nativeapp.restaurant.inventory.projection;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Read projection for an {@code ingredient_stocktake_line} row, joined to {@code ingredient} for
 * the display {@code name}/{@code unit} (ADR 0046 phase 1) — backs the native reads on {@code
 * IngredientStocktakeLineRepository}.
 */
public interface IngredientStocktakeLineView {

  UUID getId();

  UUID getIngredientStocktakeId();

  UUID getIngredientId();

  String getName();

  String getUnit();

  int getSystemQty();

  int getCountedQty();

  int getVarianceQty();

  @Nullable Long getUnitCostMinor();

  long getVarianceValueMinor();
}
