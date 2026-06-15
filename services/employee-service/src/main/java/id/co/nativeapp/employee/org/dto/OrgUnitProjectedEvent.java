package id.co.nativeapp.employee.org.dto;

import java.util.UUID;

/**
 * A decoded org event ({@code OrgUnitCreated} or {@code OrgUnitChanged}) reduced to exactly what
 * the local org read model needs — the application command the listener hands to {@link
 * OrgProjectionService}. An immutable record carrying the event id (for idempotency), the org-unit
 * id (the projection key), the owning tenant the consumer binds the write to, and the projected
 * state.
 *
 * <p>{@code legalEmployerId} is present on {@code OrgUnitCreated} and {@code null} on {@code
 * OrgUnitChanged} (the org-unit's legal employer is stable, so a change event does not restate it —
 * the writer retains the existing projected value on an update).
 *
 * @param eventId the event's UUID — the idempotency key
 * @param orgUnitId the org-unit id (the projection primary key)
 * @param companyId the tenant the consumer binds the write to (via {@code TenantContext.callAs})
 * @param legalEmployerId the org-unit's legal employer (set on create; {@code null} on change)
 * @param type the org-unit kind
 * @param active whether the org unit is active
 */
public record OrgUnitProjectedEvent(
    UUID eventId,
    UUID orgUnitId,
    String companyId,
    UUID legalEmployerId,
    String type,
    boolean active) {}
