package id.co.nativeapp.restaurant.table.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/tables}. The tenant ({@code company_id}) and actor are
 * intentionally absent — they come from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never from the client (rule 5).
 *
 * @param businessId the owning business unit
 * @param label a short human-readable label, e.g. "T1" (unique per company+business)
 * @param capacity number of seats (minimum 1)
 * @param area optional area/zone descriptor, e.g. "Indoor"
 */
public record CreateTableRequest(
    @NotNull UUID businessId, @NotBlank String label, @Min(1) int capacity, String area) {}
