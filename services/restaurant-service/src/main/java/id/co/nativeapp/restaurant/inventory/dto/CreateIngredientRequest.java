package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 */
public record CreateIngredientRequest(
    @NotNull UUID businessId,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 16) String unit,
    @Nullable @PositiveOrZero Long unitCostMinor,
    @Nullable @Size(min = 3, max = 3) String costCurrency) {}
