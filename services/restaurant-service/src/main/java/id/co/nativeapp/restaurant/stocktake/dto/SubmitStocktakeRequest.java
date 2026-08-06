package id.co.nativeapp.restaurant.stocktake.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/stocktakes} (ADR 0038 phase 3). {@code company_id} is
 * intentionally absent — it is stamped from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never trusted from the client (rule 5).
 */
public record SubmitStocktakeRequest(
    @NotNull UUID businessId, @NotEmpty @Valid List<StocktakeLineInput> lines) {}
