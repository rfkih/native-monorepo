package id.co.nativeapp.restaurant.selforderaccess.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/self-order-access/rotate} — retires the outlet's current
 * ACTIVE {@code self_order_access} row and mints a fresh one, invalidating every previously-printed
 * QR for that outlet at once.
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 *
 * @param outletId the outlet to rotate
 */
public record RotateSelfOrderAccessRequest(@NotNull UUID outletId) {}
