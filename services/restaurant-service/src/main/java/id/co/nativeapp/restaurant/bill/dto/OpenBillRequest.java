package id.co.nativeapp.restaurant.bill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/bills} — open a new guest bill.
 *
 * <p>The tenant ({@code company_id}) and actor are intentionally absent — they come from the bound
 * {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never from the client (rule 5).
 *
 * @param businessId the originating business unit
 * @param tableId nullable table reference; only for dine-in bills
 * @param guestLabel short label identifying the guest, e.g. "Guest A" or "Table 3 / Seat 2"
 */
public record OpenBillRequest(
    @NotNull UUID businessId,
    UUID tableId,
    @NotBlank @Size(max = 128) String guestLabel) {}
