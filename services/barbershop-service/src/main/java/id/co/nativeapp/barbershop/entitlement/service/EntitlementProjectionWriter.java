package id.co.nativeapp.barbershop.entitlement.service;

import id.co.nativeapp.barbershop.entitlement.domain.EntitlementProjection;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.repository.EntitlementProjectionRepository;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns the single {@code @Transactional} unit of work that applies a consumed entitlement event to
 * the local entitlement projection — idempotently — and schedules the cache invalidation on commit.
 *
 * <p>It is a distinct bean (not a private method on {@link EntitlementProjectionService}) so the
 * method is invoked through the Spring proxy: a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets
 * the tenant GUC, and the GUC is exactly what makes the RLS {@code WITH CHECK} pass on the
 * projection insert/update (rule 5). The caller binds the tenant from the event's {@code
 * company_id} before invoking this method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The dedupe claim and the projection upsert
 * happen in ONE transaction: {@link ProcessedEventStore#processOnce} claims the event UUID and runs
 * the upsert only on the FIRST delivery, so a re-delivered entitlement event is a clean no-op.
 *
 * <p><strong>Cache invalidation on commit.</strong> The shared {@code libs/entitlement-check} Redis
 * cache is a separate system; the invalidation is registered as an {@code afterCommit} {@link
 * TransactionSynchronization} (ONLY when the upsert actually ran — a deduped re-delivery skips it),
 * so a re-read after a grant/revoke sees the committed projection and a rolled-back apply never
 * invalidates. Dropping the company's whole cached view is the cheap, correct response (the next
 * gate check re-reads the projection loader and re-seeds).
 */
@Component
public class EntitlementProjectionWriter {

  private final EntitlementProjectionRepository repository;
  private final ProcessedEventStore processedEvents;

  public EntitlementProjectionWriter(
      EntitlementProjectionRepository repository, ProcessedEventStore processedEvents) {
    this.repository = repository;
    this.processedEvents = processedEvents;
  }

  /**
   * Applies the event to the projection, exactly once per event id, and registers the post-commit
   * cache invalidation. Must be called inside a {@link TenantContext} scope bound to the event's
   * {@code company_id}.
   *
   * @param onCommitted the post-commit side effect (cache invalidation), run only on a first
   *     delivery once the transaction has committed
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean apply(EntitlementProjectedEvent event, Runnable onCommitted) {
    boolean applied = processedEvents.processOnce(event.eventId(), () -> upsert(event));
    if (applied) {
      runAfterCommit(onCommitted);
    }
    return applied;
  }

  private void upsert(EntitlementProjectedEvent event) {
    String tenant = TenantContext.require().companyId();
    Optional<EntitlementProjection> existing = repository.findByModuleKey(event.moduleKey());
    if (existing.isPresent()) {
      EntitlementProjection projection = existing.get();
      projection.setEntitled(event.entitled());
      repository.save(projection);
      return;
    }
    EntitlementProjection projection =
        new EntitlementProjection(event.moduleKey(), event.entitled());
    projection.setCompanyId(tenant);
    repository.save(projection);
  }

  /**
   * Registers {@code action} to run on the current transaction's {@code afterCommit}: the
   * post-commit side effect (cache invalidation) runs only if and when THIS transaction commits, so
   * a rollback skips it. If there is no active synchronization (e.g. a unit test calling the writer
   * outside any transaction) the action runs immediately, preserving the invalidate-on-success
   * semantics.
   */
  private static void runAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
