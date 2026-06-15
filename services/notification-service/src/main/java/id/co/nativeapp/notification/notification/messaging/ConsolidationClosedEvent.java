package id.co.nativeapp.notification.notification.messaging;

import java.util.Optional;
import java.util.UUID;

/**
 * The decoded {@code ConsolidationClosed} trigger — the application command the consumer hands to
 * the notification service. An immutable record carrying exactly the fields notification needs from
 * the contract, already parsed out of the raw Avro {@link org.apache.avro.generic.GenericRecord}:
 * the source event id (idempotency), the owning tenant, the optional consolidation group, and the
 * closed period.
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request.
 *
 * @param eventId the trigger event's UUID (the idempotency key)
 * @param companyId the owning tenant the notification is created under
 * @param groupId the consolidation group id when this was a group close, else {@code null}
 * @param period the accounting period that was closed (e.g. {@code YYYY-MM})
 */
public record ConsolidationClosedEvent(
    UUID eventId, String companyId, String groupId, String period) {

  /** The consolidation group id, if this was a group-level close. */
  public Optional<String> group() {
    return Optional.ofNullable(groupId);
  }
}
