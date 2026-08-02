package id.co.nativeapp.restaurant.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/sales-channels}. The tenant ({@code company_id}) and actor
 * come from the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext} (rule 5); the
 * caller's role (owner/manager) is enforced service-side by {@code SalesChannelWriter}.
 *
 * @param code the channel code (e.g. {@code GOFOOD}); normalized to uppercase/trim by the writer,
 *     then IMMUTABLE
 * @param name the display name
 */
public record CreateSalesChannelRequest(
    @NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 120) String name) {}
