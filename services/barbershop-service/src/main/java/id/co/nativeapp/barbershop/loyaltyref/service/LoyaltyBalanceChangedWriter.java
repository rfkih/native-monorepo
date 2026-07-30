package id.co.nativeapp.barbershop.loyaltyref.service;

import id.co.nativeapp.barbershop.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.barbershop.loyaltyref.repository.MemberBalanceRefRepository;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that set-if-newer upserts a consumed {@code
 * LoyaltyBalanceChanged} event into {@code member_balance_ref} — idempotently.
 *
 * <p>A distinct bean (not a private method on {@link LoyaltyBalanceChangedService}) so the method
 * is invoked through the Spring proxy — a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC (rule
 * 5). The caller binds the tenant from the event's {@code company_id} before invoking this method.
 *
 * <p><strong>Idempotency (rule 3).</strong> {@link ProcessedEventStore#processOnce} claims the
 * event UUID and runs the upsert only on the FIRST delivery — a re-delivered event is a clean
 * no-op. <strong>Set-if-newer (ADR 0027 decision 2)</strong> is a SECOND, independent guard inside
 * the upsert SQL itself ({@code WHERE balance_seq < EXCLUDED.balance_seq}): even a first-delivery
 * event that happens to carry a stale {@code balance_seq} (out-of-order redelivery across a future
 * multi-partition topic) is dropped by the upsert without corrupting the cache — mirrors {@code
 * outletref.service.UserOutletAssignmentRefWriter}.
 */
@Component
public class LoyaltyBalanceChangedWriter {

  private final ProcessedEventStore processedEvents;
  private final MemberBalanceRefRepository repository;

  public LoyaltyBalanceChangedWriter(
      ProcessedEventStore processedEvents, MemberBalanceRefRepository repository) {
    this.processedEvents = processedEvents;
    this.repository = repository;
  }

  /**
   * Applies the event to {@code member_balance_ref}, exactly once per event id. Must be called
   * inside a {@link TenantContext} scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean apply(LoyaltyBalanceChangedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  private void upsert(LoyaltyBalanceChangedEvent event) {
    String companyId = TenantContext.require().companyId();
    repository.upsertSetIfNewer(
        event.memberId(),
        companyId,
        event.pointsBalance(),
        event.balanceSeq(),
        LoyaltyBalanceChangedService.CONSUMER_ACTOR);
  }
}
