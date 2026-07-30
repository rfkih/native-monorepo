package id.co.nativeapp.barbershop.outletref.messaging;

import java.util.UUID;

/**
 * Decoded view of a {@code UserOutletAssignmentChanged} event — the projection the consumer upserts
 * into {@code user_outlet_assignment_ref} (ported verbatim from carwash-service's {@code outletref}
 * feature). Immutable record; carries only what the consumer needs.
 *
 * <p>{@code effectiveFrom} / {@code effectiveTo} are epoch-day ints (the Avro {@code date}
 * logical-type wire encoding), matching how {@code AssignmentChanged} and {@code
 * GroupMembershipChanged} carry their effective dates.
 *
 * @param eventId the durable event UUID taken from the {@code id} Kafka header; the idempotency key
 * @param assignmentId the org-service assignment row PK; used as the barbershop table PK on insert
 * @param userId the Keycloak subject id of the assigned user
 * @param companyId the owning tenant / company id; used to bind {@code TenantContext} before upsert
 * @param orgUnitId the outlet org-unit UUID
 * @param changeKind {@code ASSIGNED} (active → true) or {@code UNASSIGNED} (active → false)
 * @param effectiveFrom epoch-day int for the assignment start date
 * @param effectiveTo epoch-day int for the assignment end date (9999-12-31 when open-ended)
 */
public record UserOutletAssignmentEvent(
    UUID eventId,
    UUID assignmentId,
    String userId,
    String companyId,
    UUID orgUnitId,
    String changeKind,
    int effectiveFrom,
    int effectiveTo) {

  /** {@code true} if the post-change state is active (an ASSIGNED event). */
  public boolean isActive() {
    return "ASSIGNED".equals(changeKind);
  }
}
