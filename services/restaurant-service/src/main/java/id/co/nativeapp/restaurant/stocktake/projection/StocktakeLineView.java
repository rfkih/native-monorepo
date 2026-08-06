package id.co.nativeapp.restaurant.stocktake.projection;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Read projection for a {@code stocktake_line} row, joined to {@code menu_item} for the display
 * {@code name} (ADR 0038 phase 3) — backs the native reads on {@code StocktakeLineRepository}.
 */
public interface StocktakeLineView {

  UUID getId();

  UUID getStocktakeId();

  UUID getMenuItemId();

  String getName();

  int getSystemQty();

  int getCountedQty();

  int getVarianceQty();

  @Nullable Long getUnitCostMinor();

  long getVarianceValueMinor();
}
