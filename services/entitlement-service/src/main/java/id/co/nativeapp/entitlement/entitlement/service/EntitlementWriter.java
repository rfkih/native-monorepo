package id.co.nativeapp.entitlement.entitlement.service;

import id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement;
import id.co.nativeapp.entitlement.entitlement.messaging.CompanyCreatedEvent;
import id.co.nativeapp.entitlement.entitlement.messaging.EntitlementEventSchema;
import id.co.nativeapp.entitlement.entitlement.repository.TenantEntitlementRepository;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns the single {@code @Transactional} units of work that mutate entitlements and write the
 * matching outbox events — granting defaults on {@code CompanyCreated}, and the explicit
 * grant/revoke of one module.
 *
 * <p>It is a distinct bean (not private methods on {@link EntitlementService}) so each
 * transactional method is invoked through the Spring proxy: a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets
 * the tenant GUC, and that GUC is exactly what makes the RLS {@code WITH CHECK} pass on the
 * entitlement inserts (rule 5). The caller binds the tenant — from the event's {@code company_id}
 * (consumer path) or the request's bound scope (API path) — before invoking these methods.
 *
 * <p><strong>Outbox-only publishing (rule 3).</strong> Every entitlement <em>status transition</em>
 * writes its {@code EntitlementGranted}/{@code EntitlementRevoked} event through {@code
 * libs/events} {@link OutboxWriter}, inside the SAME transaction that mutates the {@code
 * tenant_entitlement} row — never a direct broker call. A rollback drops both the row and the
 * event.
 *
 * <p><strong>Idempotency by aggregate state (ENGINEERING-STANDARDS §1.3).</strong> An entitlement
 * event fires ONLY on a real status change, so a write is idempotent by its natural {@code
 * (company_id, module_key)} state key — no client {@code Idempotency-Key} header is needed.
 * Granting a module already {@code ENTITLED} re-stamps nothing material and emits NO second {@code
 * EntitlementGranted}; revoking a module that is not currently {@code ENTITLED} (already {@code
 * REVOKED}) emits NO second {@code EntitlementRevoked}. This holds for EVERY grant path — the API
 * grant and the {@code CompanyCreated} default-grant both funnel through {@link #grantOne}, so a
 * re-grant from any source is event-idempotent. {@link WriteOutcome} carries back to the caller
 * both the persisted row and whether this call was an actual transition (so the caller knows
 * whether the cache view changed).
 *
 * <p><strong>Cache seeding/invalidation is deferred to the caller.</strong> The Redis
 * entitlement-check cache is a separate system; the {@link EntitlementService} invalidates it on
 * the transaction's {@code afterCommit} callback, so a re-read sees the committed state and a
 * rolled-back write never invalidates. This writer is purely the transactional DB + outbox unit.
 */
@Component
public class EntitlementWriter {

  private final TenantEntitlementRepository entitlementRepository;
  private final OutboxWriter outboxWriter;
  private final ProcessedEventStore processedEvents;
  private final PostOutboxHook postOutboxHook;

  public EntitlementWriter(
      TenantEntitlementRepository entitlementRepository,
      OutboxWriter outboxWriter,
      ProcessedEventStore processedEvents,
      PostOutboxHook postOutboxHook) {
    this.entitlementRepository = entitlementRepository;
    this.outboxWriter = outboxWriter;
    this.processedEvents = processedEvents;
    this.postOutboxHook = postOutboxHook;
  }

  /**
   * Grants a company its DEFAULT set of modules on first {@code CompanyCreated}, idempotently and
   * atomically. The whole unit — the dedupe claim, one {@code tenant_entitlement} row per default
   * module, and one {@code EntitlementGranted} outbox event per grant — commits (or rolls back)
   * together. {@link ProcessedEventStore#processOnce} claims the event UUID inside this
   * transaction, so a re-delivered {@code CompanyCreated} (same event id) is a clean no-op: no
   * second grants, no second events.
   *
   * <p>Must be called inside a {@link TenantContext} scope bound to the event's {@code company_id}
   * (the auto-RLS aspect sets the tenant GUC for this transaction from that scope), so the {@code
   * WITH CHECK} passes on every default grant for the brand-new tenant.
   *
   * @param event the decoded {@code CompanyCreated} (carries the idempotency event id + company id)
   * @param defaultModules the default module keys to grant (validated against {@code
   *     module_catalog} at config/service load)
   * @return {@code true} if this delivery granted the defaults (first delivery), {@code false} if
   *     it was a re-delivery skipped by the idempotent consumer.
   */
  @Transactional
  public boolean grantDefaults(CompanyCreatedEvent event, List<String> defaultModules) {
    return processedEvents.processOnce(
        event.eventId(), () -> grantEach(defaultModules, Instant.now()));
  }

  private void grantEach(List<String> defaultModules, Instant grantedAt) {
    for (String moduleKey : defaultModules) {
      // grantOne is itself event-idempotent: re-granting an already-ENTITLED default emits no
      // second
      // event (the whole CompanyCreated delivery is also deduped by processOnce above).
      grantOne(moduleKey, grantedAt);
    }
  }

  /**
   * Grants a single module to the bound tenant, idempotently by aggregate state. Upserts the {@code
   * (company_id, module_key)} row to {@code ENTITLED} (creating it, or re-granting a
   * previously-{@code REVOKED} one) and — only when this is an actual transition <em>to</em> {@code
   * ENTITLED} — writes exactly one {@code EntitlementGranted} outbox event in the same transaction.
   * A grant of a module that is ALREADY {@code ENTITLED} is a no-op: it emits NO second event (rule
   * 3 / §1.3, no duplicate events on a repeated command).
   *
   * <p>Must be called inside a bound {@link TenantContext} scope; the row's {@code company_id} is
   * stamped from that scope (never from a request body), so RLS confines it to the bound tenant.
   *
   * <p>{@code onTransitionCommitted} is registered as an {@code afterCommit} {@link
   * TransactionSynchronization} ONLY when this call is an actual transition, so a rolled-back grant
   * never runs it and a no-op (already-{@code ENTITLED}) grant does not either — this is how the
   * caller defers the cache invalidation to a successful commit (FIX 3 / §3.2).
   *
   * @param onTransitionCommitted post-commit side effect (cache invalidation), run only on a real
   *     transition once the transaction has committed
   * @return the persisted entitlement (status {@code ENTITLED}) plus whether this call was an
   *     actual transition that emitted an event.
   */
  @Transactional
  public WriteOutcome grant(String moduleKey, Runnable onTransitionCommitted) {
    return grantOne(moduleKey, Instant.now(), onTransitionCommitted);
  }

  private WriteOutcome grantOne(String moduleKey, Instant grantedAt) {
    // The default-grant path registers no post-commit side effect of its own; the consumer service
    // invalidates the whole company view once after the batch commits.
    return grantOne(moduleKey, grantedAt, () -> {});
  }

  private WriteOutcome grantOne(
      String moduleKey, Instant grantedAt, Runnable onTransitionCommitted) {
    String tenant = TenantContext.require().companyId();
    Optional<TenantEntitlement> found = entitlementRepository.findByModuleKey(moduleKey);

    // Idempotency by aggregate state: a module already ENTITLED is a no-op — no row mutation worth
    // re-stamping, and critically NO second EntitlementGranted (§1.3, no duplicate events).
    if (found.isPresent() && found.get().isEntitled()) {
      return new WriteOutcome(found.get(), false);
    }

    TenantEntitlement entitlement =
        found
            .map(
                existing -> {
                  existing.grant(grantedAt);
                  return existing;
                })
            .orElseGet(
                () -> {
                  TenantEntitlement fresh = new TenantEntitlement(moduleKey, grantedAt);
                  fresh.setCompanyId(tenant);
                  return fresh;
                });
    TenantEntitlement saved = entitlementRepository.save(entitlement);
    entitlementRepository.flush();

    GenericRecord event = EntitlementEventSchema.granted(tenant, moduleKey, grantedAt);
    writeOutbox(
        tenant, EntitlementEventSchema.GRANTED_EVENT_TYPE, AvroSerde.serialize(event), grantedAt);

    // Test seam: a no-op in production; the atomicity test installs a hook that throws here, AFTER
    // the outbox write but still inside this transaction, to prove the entitlement row AND the
    // outbox row roll back together (rule 3). It runs BEFORE the afterCommit registration so a
    // forced failure here rolls back the write AND skips the post-commit invalidation.
    postOutboxHook.afterOutboxWrite(saved);
    runAfterCommit(onTransitionCommitted);
    return new WriteOutcome(saved, true);
  }

  /**
   * Revokes a single module from the bound tenant, idempotently by aggregate state. Flips the
   * existing {@code (company_id, module_key)} row to {@code REVOKED} (stamping {@code revoked_at})
   * and — only when this is an actual transition <em>away from</em> {@code ENTITLED} — writes
   * exactly one {@code EntitlementRevoked} outbox event in the same transaction. A revoke of a row
   * that is already {@code REVOKED} is a no-op: it emits NO second event (§1.3). The module must
   * already have an entitlement row (the service rejects revoking a never-granted module up front).
   *
   * <p>As with {@link #grant}, {@code onTransitionCommitted} is registered as an {@code
   * afterCommit} synchronization ONLY on a real transition, so a rolled-back or no-op revoke never
   * invalidates the cache (FIX 3 / §3.2).
   *
   * @param onTransitionCommitted post-commit side effect (cache invalidation), run only on a real
   *     transition once the transaction has committed
   * @return the persisted entitlement (status {@code REVOKED}) plus whether this call was an actual
   *     transition that emitted an event.
   */
  @Transactional
  public WriteOutcome revoke(TenantEntitlement existing, Runnable onTransitionCommitted) {
    // Idempotency by aggregate state: a row already REVOKED is a no-op — no second
    // EntitlementRevoked
    // (§1.3, no duplicate events on a re-revoke).
    if (!existing.isEntitled()) {
      return new WriteOutcome(existing, false);
    }
    String tenant = TenantContext.require().companyId();
    Instant revokedAt = Instant.now();
    existing.revoke(revokedAt);
    TenantEntitlement saved = entitlementRepository.save(existing);
    entitlementRepository.flush();

    GenericRecord event =
        EntitlementEventSchema.revoked(tenant, existing.getModuleKey(), revokedAt);
    writeOutbox(
        tenant, EntitlementEventSchema.REVOKED_EVENT_TYPE, AvroSerde.serialize(event), revokedAt);
    runAfterCommit(onTransitionCommitted);
    return new WriteOutcome(saved, true);
  }

  /**
   * Registers {@code action} to run on the current transaction's {@code afterCommit} (FIX 3): the
   * post-commit side effect (cache invalidation) runs only if and when THIS transaction commits, so
   * a rollback skips it entirely. Registration happens here, inside the {@code @Transactional}
   * unit, because {@link TransactionSynchronizationManager#registerSynchronization} requires an
   * active synchronization — by the time control returns to the (non-transactional) service the
   * transaction has already committed. If there is no active synchronization (e.g. a unit test
   * calling the writer outside any transaction) the action runs immediately, preserving the
   * invalidate-on-success semantics.
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

  private void writeOutbox(String tenant, String eventType, byte[] payload, Instant occurredAt) {
    // aggregate_id = company_id (the partition key, per ARCHITECTURE.md §5); company_id on the
    // outbox row is a UUID, and the tenant id is a UUID by construction.
    outboxWriter.write(
        EntitlementEventSchema.AGGREGATE_TYPE,
        tenant,
        eventType,
        payload,
        null,
        UUID.fromString(tenant),
        occurredAt);
  }

  /**
   * The result of a grant/revoke: the persisted {@link TenantEntitlement} plus whether this call
   * was an actual status transition (so it emitted an event). {@code transitioned == false} means
   * the row was already in the target state — a no-op that emitted NO event, and which the caller
   * need not invalidate the cache for (its cached view did not change).
   *
   * @param entitlement the persisted (or already-current) entitlement row
   * @param transitioned {@code true} if the status actually changed and an event was emitted
   */
  public record WriteOutcome(TenantEntitlement entitlement, boolean transitioned) {}
}
