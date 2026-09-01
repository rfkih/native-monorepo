package id.co.nativeapp.employee.org.service;

import id.co.nativeapp.employee.org.domain.OrgUnitProjection;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.dto.OrgUnitRemovedEvent;
import id.co.nativeapp.employee.org.repository.OrgUnitProjectionRepository;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that applies a consumed org event to the
 * local org read model — idempotently.
 *
 * <p>It is a distinct bean (not a private method on {@link OrgProjectionService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC, and
 * the GUC is exactly what makes the RLS {@code WITH CHECK} pass on the projection insert/update
 * (rule 5). The caller binds the tenant from the event's {@code company_id} before invoking this
 * method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The dedupe claim and the projection upsert
 * happen in ONE transaction: {@link ProcessedEventStore#processOnce} claims the event UUID and runs
 * the upsert only on the FIRST delivery, so a re-delivered org event is a clean no-op.
 *
 * <p><strong>Create vs change.</strong> {@code OrgUnitCreated} carries the legal employer and
 * creates the projection row (or refreshes it if it already exists from a replay); {@code
 * OrgUnitChanged} does not restate the legal employer (it is stable), so an update keeps the
 * existing projected legal employer and only refreshes type + active. A change for an org unit not
 * yet projected (events out of order) creates the row defensively, leaving its legal employer to be
 * corrected by the eventual create replay.
 */
@Component
public class OrgProjectionWriter {

  private final OrgUnitProjectionRepository projectionRepository;
  private final ProcessedEventStore processedEvents;

  public OrgProjectionWriter(
      OrgUnitProjectionRepository projectionRepository, ProcessedEventStore processedEvents) {
    this.projectionRepository = projectionRepository;
    this.processedEvents = processedEvents;
  }

  /**
   * Applies the event to the projection, exactly once per event id. Must be called inside a {@link
   * TenantContext} scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean apply(OrgUnitProjectedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  /**
   * Purges the projection row for a permanently deleted org unit, exactly once per event id (ADR
   * 0070). Must be called inside a {@link TenantContext} scope bound to the event's {@code
   * company_id} — RLS then scopes the lookup, so a cross-tenant id resolves empty and deletes
   * nothing (rule 5).
   *
   * <p>Deleting an absent projection is a no-op: employee-service may never have projected the unit
   * (e.g. it was created and deleted before this consumer existed), which is the correct outcome.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean remove(OrgUnitRemovedEvent removal) {
    return processedEvents.processOnce(
        removal.eventId(), () -> projectionRepository.deleteById(removal.orgUnitId()));
  }

  private void upsert(OrgUnitProjectedEvent event) {
    String tenant = TenantContext.require().companyId();
    Optional<OrgUnitProjection> existing = projectionRepository.findById(event.orgUnitId());

    if (existing.isPresent()) {
      OrgUnitProjection projection = existing.get();
      projection.applyChange(event.type(), event.active());
      projectionRepository.save(projection);
      return;
    }

    // New projection row. legalEmployerId is present on OrgUnitCreated; for an out-of-order
    // OrgUnitChanged that arrives before its create, the org-unit id doubles as a placeholder
    // legal employer (one legal_employer per company today, corrected by the create replay).
    java.util.UUID legalEmployerId =
        event.legalEmployerId() != null ? event.legalEmployerId() : event.orgUnitId();
    OrgUnitProjection projection =
        new OrgUnitProjection(event.orgUnitId(), legalEmployerId, event.type(), event.active());
    projection.setCompanyId(tenant);
    projectionRepository.save(projection);
  }
}
