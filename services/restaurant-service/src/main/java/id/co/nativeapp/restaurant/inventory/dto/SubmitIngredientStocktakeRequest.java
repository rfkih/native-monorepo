package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/ingredient-stocktakes} (ADR 0046 phase 1). {@code
 * company_id} is intentionally absent — it is stamped from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never trusted from the client (rule 5).
 */
public record SubmitIngredientStocktakeRequest(
    @NotNull UUID businessId, @NotEmpty @Valid List<IngredientStocktakeLineInput> lines) {}
