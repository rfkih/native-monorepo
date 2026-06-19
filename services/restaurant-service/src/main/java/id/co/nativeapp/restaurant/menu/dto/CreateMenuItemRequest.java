package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/menu}. The monetary price is {@code priceMinor} (integer
 * minor units) + ISO-4217 {@code currency} (never a float, rule 8). {@code company_id} is
 * intentionally absent — it is stamped from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never trusted from the client (rule 5).
 */
public record CreateMenuItemRequest(
    @NotNull UUID businessId,
    @NotBlank String name,
    @NotBlank String category,
    @NotNull @Positive Long priceMinor,
    @NotBlank String currency) {}
