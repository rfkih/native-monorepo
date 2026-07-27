package id.co.nativeapp.restaurant.outletref.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that upserts a consumed {@code
 * UserOutletAssignmentChanged} event into the {@code user_outlet_assignment_ref} local read model —
 * idempotently.
 *
 * <p>It is a distinct bean (not a private method on {@link UserOutletAssignmentRefService}) so the
 * method is invoked through the Spring proxy: a self-invocation would bypass the {@code
 * @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the
 * tenant GUC — without the GUC the RLS {@code WITH CHECK} fails closed (rule 5). The caller
 * ({@link UserOutletAssignmentRefService}) binds the tenant from the event's {@code company_id}
 * before invoking this method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> {@link ProcessedEventStore#processOnce} claims
 * the event UUID and runs the upsert only on the FIRST delivery. A re-delivered event is a clean
 * no-op — the {@code processed_event} row carries the claim from the first delivery and {@code
 * processOnce} returns {@code false} without running the upsert.
 *
 * <p><strong>Upsert strategy.</strong> An atomic {@code INSERT … ON CONFLICT ON CONSTRAINT
 * uq_user_outlet_assignment_ref_scope DO UPDATE} via {@link JdbcTemplate} handles both ASSIGNED and
 * UNASSIGNED in one statement, with no SELECT-then-insert race. Both event kinds carry the
 * post-change state ({@code active} = true for ASSIGNED, false for UNASSIGNED), so the upsert
 * always converges to the producer's last-known state. The conflict target is the TENANT-COMPOSITE
 * unique constraint ({@code company_id, user_id, org_unit_id}), not the bare PK: if two tenants
 * ever collided on an {@code assignment_id} (astronomically unlikely with UUIDv4), the second
 * tenant's insert would trip the PK violation and fail closed to the DLT rather than updating the
 * first tenant's row.
 */
@Component
public class UserOutletAssignmentRefWriter {

  /** The system actor stamped into {@code created_by} / {@code updated_by}. */
  static final String CONSUMER_ACTOR = "org-assignment-consumer";

  private final ProcessedEventStore processedEvents;
  private final JdbcTemplate jdbcTemplate;

  public UserOutletAssignmentRefWriter(
      ProcessedEventStore processedEvents, JdbcTemplate jdbcTemplate) {
    this.processedEvents = processedEvents;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Applies the event to {@code user_outlet_assignment_ref}, exactly once per event id. Must be
   * called inside a {@link TenantContext} scope bound to the event's {@code company_id} so the RLS
   * GUC is set.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean apply(UserOutletAssignmentEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  private void upsert(UserOutletAssignmentEvent event) {
    String companyId = TenantContext.require().companyId();
    Timestamp now = Timestamp.from(Instant.now());

    // Atomic INSERT … ON CONFLICT … DO UPDATE so both ASSIGNED and UNASSIGNED converge to the
    // producer's current state without a SELECT-then-insert race.
    // Conflict target is the TENANT-COMPOSITE unique constraint, not the bare PK (belt +
    // suspenders: a cross-tenant assignment_id collision fails on the PK, not on the constraint).
    jdbcTemplate.update(
        """
        INSERT INTO user_outlet_assignment_ref
          (assignment_id, user_id, org_unit_id, active,
           created_at, created_by, updated_at, updated_by, version, company_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
        ON CONFLICT ON CONSTRAINT uq_user_outlet_assignment_ref_scope DO UPDATE SET
          active      = EXCLUDED.active,
          updated_at  = EXCLUDED.updated_at,
          updated_by  = EXCLUDED.updated_by,
          version     = user_outlet_assignment_ref.version + 1
        """,
        event.assignmentId(),
        event.userId(),
        event.orgUnitId(),
        event.isActive(),
        now,
        CONSUMER_ACTOR,
        now,
        CONSUMER_ACTOR,
        companyId);
  }
}
