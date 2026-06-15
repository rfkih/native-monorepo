package id.co.nativeapp.finance.group.messaging;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The decoded {@code GroupMembershipChanged} event (P3d SEAM 1) — already parsed out of the raw
 * Avro {@link org.apache.avro.generic.GenericRecord}. Carries the membership's post-change state so
 * the consumer mirrors it idempotently into the local {@link GroupMember} read model.
 *
 * <p>The event carries NO {@code lead_company_id}; finance resolves the owning lead from its local
 * {@link GroupRef} (keyed on {@code group_id}) — a cached read model, never a synchronous call
 * (rule 2). A membership event for a group finance has not yet seen a {@code GroupDefined} for is
 * deferred (re-delivered) rather than projected under an unknown tenant.
 *
 * @param eventId the source event UUID (idempotency key)
 * @param groupId the consolidation group id
 * @param memberCompanyId the member company added or removed
 * @param changeKind ADDED | REMOVED
 * @param effectiveFrom the membership's effective-from date (post-change)
 * @param effectiveTo the membership's effective-to date (the sentinel while open; the close date on
 *     a removal)
 */
public record GroupMembershipChangedEvent(
    UUID eventId,
    UUID groupId,
    UUID memberCompanyId,
    String changeKind,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
