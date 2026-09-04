package id.co.nativeapp.restaurant.inventory.projection;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Read projection for an {@code ingredient} row (ADR 0046 phase 1) — backs the native list read on
 * {@code IngredientRepository}.
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface IngredientView {

  UUID getId();

  UUID getBusinessId();

  String getName();

  String getUnit();

  /** Display-unit label (e.g. {@code kg} over a base of {@code g}); {@code null} = base unit. */
  @Nullable String getDisplayUnit();

  int getStockQty();

  @Nullable Long getUnitCostMinor();

  @Nullable String getCostCurrency();

  /** Total acquisition value of stock on hand, in {@code cost_currency}'s minor units (V36). */
  long getStockValueMinor();

  boolean isActive();
}
