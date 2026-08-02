package id.co.nativeapp.restaurant.channel.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/sales-channels/{id}}. Both fields are optional (unset =
 * unchanged); {@code code} is deliberately absent — it is immutable once created (see {@code
 * SalesChannel}).
 *
 * @param name optional new display name
 * @param active optional new active flag (soft-activate/deactivate)
 */
public record UpdateSalesChannelRequest(@Size(max = 120) String name, Boolean active) {}
