package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/menu/categories}. The tenant ({@code company_id}) comes from
 * the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never from the client.
 *
 * @param businessId the owning business unit
 * @param name the display name for this category
 * @param displayOrder the sort position (lower = higher in the list); defaults to 0
 */
public record CreateCategoryRequest(
    @NotNull UUID businessId, @NotBlank String name, @Min(0) int displayOrder) {}
