package id.co.nativeapp.finance.orgref.messaging;

import java.util.UUID;

/**
 * A decoded org event ({@code OrgUnitCreated} or {@code OrgUnitChanged}) reduced to exactly what
 * the {@code org_unit_ref} local read model needs — the application command the listener hands to
 * {@link id.co.nativeapp.finance.orgref.service.OrgUnitRefService}. An immutable record carrying
 * the event id (for idempotency), the org-unit id (the upsert primary key), the owning tenant the
 * consumer binds the write to, and the node's projected state.
 *
 * <p>The event carries the node's CURRENT state (after the change for {@code OrgUnitChanged}, at
 * creation for {@code OrgUnitCreated}), so the upsert is always idempotent: a re-delivery of the
 * same event id writes the exact same state and the {@code ProcessedEventStore} dedupe claim makes
 * it a no-op on any re-delivery after the first (rule 3 / HR-3).
 *
 * @param eventId the event's UUID — the idempotency key ({@code id} Kafka header)
 * @param orgUnitId the org-unit id (the {@code org_unit_ref} primary key)
 * @param companyId the tenant the consumer binds the write to (via {@code TenantContext.callAs})
 * @param type the org-unit kind (business_unit | branch | outlet | team)
 * @param parentId the parent org-unit id, or {@code null} for a top-level node
 * @param name the org-unit display name
 * @param active whether the org unit is currently active
 */
public record OrgUnitRefEvent(
    UUID eventId,
    UUID orgUnitId,
    String companyId,
    String type,
    UUID parentId,
    String name,
    boolean active) {}
