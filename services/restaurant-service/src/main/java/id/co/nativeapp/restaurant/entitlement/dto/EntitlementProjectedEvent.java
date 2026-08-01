package id.co.nativeapp.restaurant.entitlement.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A decoded entitlement event ({@code EntitlementGranted} or {@code EntitlementRevoked}) reduced to
 * exactly what the local entitlement projection needs — the application command the listener hands
 * to {@link id.co.nativeapp.restaurant.entitlement.service.EntitlementProjectionService}. An
 * immutable record carrying the event id (for idempotency), the owning tenant the consumer binds
 * the write to, the module key (the projection key within the tenant), the new entitled state
 * ({@code true} for a grant, {@code false} for a revoke), and the instant the event's producer
 * stamped it (the {@code granted_at}/{@code revoked_at} field — a monotonic per-(company, module)
 * timestamp used as a set-if-newer ordering guard, since {@code EntitlementGranted}/{@code
 * EntitlementRevoked} arrive on two separate topics with independent lag and no ordering guarantee
 * between them).
 *
 * @param eventId the event's UUID — the idempotency key
 * @param companyId the tenant the consumer binds the write to (via {@code TenantContext.callAs})
 * @param moduleKey the module this projection row is for
 * @param entitled the new entitled state (grant -> true, revoke -> false)
 * @param occurredAt when the grant/revoke took effect upstream — the ordering guard the projection
 *     upsert applies (a stale/reordered event is dropped, never applied over a newer one)
 */
public record EntitlementProjectedEvent(
    UUID eventId, String companyId, String moduleKey, boolean entitled, Instant occurredAt) {}
