package id.co.nativeapp.finance.orgref.messaging;

import java.util.UUID;

/**
 * A decoded {@code OrgUnitDeleted} reduced to exactly what purging the {@code org_unit_ref} local
 * read model needs — the application command the listener hands to {@link
 * id.co.nativeapp.finance.orgref.service.OrgUnitRefService}.
 *
 * <p>Deliberately a separate record from {@link OrgUnitRefEvent}: that one carries a node's
 * projected STATE for an upsert, whereas a deletion has no state to project — modelling it as an
 * upsert with placeholder fields would let a malformed event write a ghost row. The two paths stay
 * structurally unable to be confused.
 *
 * <p>Idempotent by construction: the delete is claimed once per event id by {@code
 * ProcessedEventStore}, and a delete of an already-absent row is a no-op anyway (ADR 0070).
 *
 * @param eventId the event's UUID — the idempotency key ({@code id} Kafka header)
 * @param orgUnitId the org-unit id to purge from {@code org_unit_ref}
 * @param companyId the tenant the consumer binds the delete to (via {@code TenantContext.callAs}),
 *     so RLS scopes it (rule 5)
 */
public record OrgUnitRefRemoval(UUID eventId, UUID orgUnitId, String companyId) {}
