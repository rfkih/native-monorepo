package id.co.nativeapp.carwash.entitlement;

import id.co.nativeapp.entitlementcheck.CachedEntitlementChecker;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed entitlement event to the local entitlement projection and keeps
 * the shared entitlement-check Redis cache consistent.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> There is no JWT on
 * the consumer path, so this service binds the tenant scope from the event's {@code company_id} via
 * {@link TenantContext#callAs} with a fixed {@code "carwash-entitlement-consumer"} actor (which
 * lands in the Auditable {@code created_by}), then delegates to the proxied {@link
 * EntitlementProjectionWriter} so the {@code @Transactional} advice and the auto-RLS aspect engage
 * under that tenant. The transactional unit of work — dedupe + projection upsert — lives in the
 * writer (a separate bean so the Spring proxy is not bypassed by self-invocation).
 *
 * <p><strong>Cache invalidation on commit.</strong> The writer registers the {@link
 * CachedEntitlementChecker#invalidate(String) invalidate} as an {@code afterCommit} callback (only
 * on a first delivery), so after a grant/revoke commits the company's cached view is dropped and
 * the next record-wash gate check re-reads the projection loader — promptly opening or closing the
 * gate.
 */
@Service
public class EntitlementProjectionService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "carwash-entitlement-consumer";

  private final EntitlementProjectionWriter writer;
  private final CachedEntitlementChecker entitlementChecker;

  public EntitlementProjectionService(
      EntitlementProjectionWriter writer, CachedEntitlementChecker entitlementChecker) {
    this.writer = writer;
    this.entitlementChecker = entitlementChecker;
  }

  /**
   * Applies one decoded entitlement event, idempotently, in the event's tenant scope, then (on
   * commit, first delivery only) invalidates the company's entitlement-check cache.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean apply(EntitlementProjectedEvent event) {
    try {
      return TenantContext.callAs(
          event.companyId(),
          CONSUMER_ACTOR,
          () -> writer.apply(event, () -> entitlementChecker.invalidate(event.companyId())));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply entitlement event", e);
    }
  }
}
