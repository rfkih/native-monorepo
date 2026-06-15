package id.co.nativeapp.finance.group;

import java.util.UUID;

/**
 * The decoded {@code GroupDefined} event (P3d SEAM 1) — already parsed out of the raw Avro {@link
 * org.apache.avro.generic.GenericRecord} into exactly the fields finance needs to register the
 * group in its local {@link GroupRef} read model.
 *
 * @param eventId the source event UUID (idempotency key)
 * @param groupId the consolidation group id
 * @param leadCompanyId the lead/owner company — the tenant finance binds the projection write to
 * @param reportingCurrency the group's immutable ISO-4217 reporting currency
 * @param name the group display name (carried for completeness; not projected in SEAM 1)
 */
public record GroupDefinedEvent(
    UUID eventId, UUID groupId, UUID leadCompanyId, String reportingCurrency, String name) {}
