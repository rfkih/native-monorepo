package id.co.nativeapp.employee.org.dto;

import java.util.UUID;

/**
 * A decoded {@code OrgUnitDeleted} reduced to exactly what purging the local org read model needs —
 * the application command the listener hands to {@link
 * id.co.nativeapp.employee.org.service.OrgProjectionService}.
 *
 * <p>Deliberately a separate record from {@link OrgUnitProjectedEvent}: that one carries a node's
 * projected STATE for an upsert, whereas a deletion has none — modelling it as an upsert with
 * placeholder fields would let a malformed event write a ghost projection row. The two paths stay
 * structurally unable to be confused.
 *
 * <p>Idempotent by construction: the delete is claimed once per event id by {@code
 * ProcessedEventStore}, and deleting an already-absent projection is a no-op anyway (ADR 0070).
 *
 * @param eventId the event's UUID — the idempotency key ({@code id} Kafka header)
 * @param orgUnitId the org-unit id to purge from the projection
 * @param companyId the tenant the consumer binds the delete to (via {@code TenantContext.callAs}),
 *     so RLS scopes it (rule 5)
 */
public record OrgUnitRemovedEvent(UUID eventId, UUID orgUnitId, String companyId) {}
