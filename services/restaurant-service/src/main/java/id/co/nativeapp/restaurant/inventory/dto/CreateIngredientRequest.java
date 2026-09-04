package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code POST /api/v1/ingredients} (ADR 0046 phase 1). {@code company_id} is
 * intentionally absent — it is stamped from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never trusted from the client (rule 5).
 *
 * <p>{@code unitCostMinor}/{@code costCurrency} are an optional pair: both present records a cost
 * (used to value a stock opname variance), both absent means the ingredient is counted
 * operationally with no ledger impact. {@code Ingredient#setUnitCost} enforces the both-or-neither
 * invariant.
 *
 * <p>{@code initialStockQty} seeds the on-hand quantity at creation ({@code null} = 0). Without it
 * a costed ingredient created "with stock" would start at 0 and its first opname would book the
 * whole opening count as a phantom inventory GAIN (review finding, ADR 0046).
 */
public record CreateIngredientRequest(
    @NotNull UUID businessId,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 16) String unit,
    @Nullable @Size(max = 16) String displayUnit,
    @Nullable @PositiveOrZero Long unitCostMinor,
    @Nullable @Size(min = 3, max = 3) String costCurrency,
    @Nullable @PositiveOrZero Integer initialStockQty,
    @Nullable @Positive Integer packSize) {

  /** Pre-pack-size callers (and every existing test) keep compiling unchanged. */
  public CreateIngredientRequest(
      UUID businessId,
      String name,
      String unit,
      @Nullable String displayUnit,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency,
      @Nullable Integer initialStockQty) {
    this(businessId, name, unit, displayUnit, unitCostMinor, costCurrency, initialStockQty, null);
  }
}
