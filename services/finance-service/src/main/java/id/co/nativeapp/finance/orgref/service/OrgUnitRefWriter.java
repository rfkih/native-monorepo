package id.co.nativeapp.finance.orgref.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.orgref.messaging.OrgUnitRefEvent;
import id.co.nativeapp.finance.orgref.repository.OrgUnitRefRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that upserts a consumed org-unit event into
 * the {@code org_unit_ref} local read model — idempotently.
 *
 * <p>It is a distinct bean (not a private method on {@link OrgUnitRefService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC —
 * without the GUC the RLS {@code WITH CHECK} fails closed (rule 5). The caller ({@link
 * OrgUnitRefService}) binds the tenant from the event's {@code company_id} before invoking this
 * method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> {@link ProcessedEventStore#processOnce} claims
 * the event UUID and runs the upsert only on the FIRST delivery. A re-delivered org event is a
 * clean no-op — the {@code processed_event} row carries the claim from the first delivery and the
 * {@code processOnce} returns {@code false} without running the upsert.
 *
 * <p><strong>Upsert strategy.</strong> An atomic {@code INSERT … ON CONFLICT (company_id,
 * org_unit_id) DO UPDATE} via {@code JdbcTemplate} handles both creation and change in one
 * statement, with no SELECT-then-insert race. Both {@code OrgUnitCreated} and {@code
 * OrgUnitChanged} carry the node's FULL current state (name, type, parent, active), so the upsert
 * always converges to the producer's last-known state regardless of delivery order. The conflict
 * target is the TENANT-COMPOSITE unique constraint, not the bare PK: if two tenants ever collided
 * on an {@code org_unit_id}, the second tenant's insert would trip the PK violation and fail
 * closed to the DLT rather than updating the first tenant's row.
 */
@Component
public class OrgUnitRefWriter {

  /**
   * The system actor stamped into the Auditable {@code created_by} / {@code updated_by} columns.
   */
  static final String CONSUMER_ACTOR = "finance-orgref-consumer";

  private final OrgUnitRefRepository orgUnitRefRepository;
  private final ProcessedEventStore processedEvents;
  private final JdbcTemplate jdbcTemplate;

  public OrgUnitRefWriter(
      OrgUnitRefRepository orgUnitRefRepository,
      ProcessedEventStore processedEvents,
      JdbcTemplate jdbcTemplate) {
    this.orgUnitRefRepository = orgUnitRefRepository;
    this.processedEvents = processedEvents;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Applies the event to {@code org_unit_ref}, exactly once per event id. Must be called inside a
   * {@link TenantContext} scope bound to the event's {@code company_id} so the RLS GUC is set.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean apply(OrgUnitRefEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  private void upsert(OrgUnitRefEvent event) {
    String companyId = TenantContext.require().companyId();
    Timestamp now = Timestamp.from(Instant.now());

    // Atomic INSERT … ON CONFLICT … DO UPDATE so both OrgUnitCreated and OrgUnitChanged
    // converge to the producer's current state without a SELECT-then-insert race.
    jdbcTemplate.update(
        """
        INSERT INTO org_unit_ref
          (org_unit_id, type, parent_id, name, active,
           created_at, created_by, updated_at, updated_by, version, company_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
        ON CONFLICT (company_id, org_unit_id) DO UPDATE SET
          type       = EXCLUDED.type,
          parent_id  = EXCLUDED.parent_id,
          name       = EXCLUDED.name,
          active     = EXCLUDED.active,
          updated_at = EXCLUDED.updated_at,
          updated_by = EXCLUDED.updated_by,
          version    = org_unit_ref.version + 1
        """,
        event.orgUnitId(),
        event.type(),
        event.parentId(),
        event.name(),
        event.active(),
        now,
        CONSUMER_ACTOR,
        now,
        CONSUMER_ACTOR,
        companyId);
  }
}
