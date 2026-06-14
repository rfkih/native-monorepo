package id.co.nativeapp.entitlement.entitlement;

import id.co.nativeapp.entitlement.config.EntitlementProperties;
import id.co.nativeapp.entitlement.entitlement.EntitlementWriter.WriteOutcome;
import id.co.nativeapp.entitlementcheck.CachedEntitlementChecker;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the entitlement use cases — granting a new company its defaults on {@code
 * CompanyCreated}, and the explicit grant/revoke of one module on the API — and keeps the shared
 * entitlement-check Redis cache consistent.
 *
 * <p><strong>Not transactional itself.</strong> The transactional units (DB write + outbox event)
 * live in {@link EntitlementWriter} (a separate proxied bean) so the {@code @Transactional} advice
 * and the auto-RLS aspect engage; this service composes them, binds the tenant where the inbound
 * tenant comes from an event (the consumer path), validates module keys against the catalog, and
 * invalidates the Redis cache once the write commits so a re-read sees the committed state.
 *
 * <p><strong>Idempotency by aggregate state (ENGINEERING-STANDARDS §1.3).</strong> Entitlement
 * writes dedupe on their natural {@code (company_id, module_key)} state key, so no client {@code
 * Idempotency-Key} header is required. A repeated grant of an already-{@code ENTITLED} module, or a
 * repeated revoke of an already-{@code REVOKED} one, is a no-op: it emits NO second event and
 * (since nothing changed) does NOT re-invalidate the cache. {@link EntitlementWriter} reports back
 * whether a call was an actual transition via {@link WriteOutcome#transitioned()}.
 *
 * <p><strong>Cache invalidation runs on commit, not before.</strong> On a grant/revoke that
 * actually transitions, the invalidation is registered as a {@link TransactionSynchronization}
 * {@code afterCommit} callback rather than executed inline after the writer returns, so a
 * rolled-back write never invalidates and the {@link CachedEntitlementChecker#invalidate(String)
 * invalidate} runs only against committed truth. Invalidation drops the company's whole cached view
 * rather than poking individual entries: the next entitlement check re-reads the DB loader and
 * re-seeds the cache (read-through) — the single, cheap operation the ARCHITECTURE.md §2
 * "invalidated by its events" contract calls for. A crash in the narrow window between the commit
 * and the {@code afterCommit} invalidation is bounded by the cache TTL (the known cache-aside
 * limitation — the next read after the TTL re-seeds the committed truth).
 */
@Service
public class EntitlementService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "entitlement-consumer";

  private final EntitlementWriter writer;
  private final EntitlementReader reader;
  private final CachedEntitlementChecker entitlementChecker;
  private final List<String> defaultModules;

  public EntitlementService(
      EntitlementWriter writer,
      EntitlementReader reader,
      CachedEntitlementChecker entitlementChecker,
      EntitlementProperties properties) {
    this.writer = writer;
    this.reader = reader;
    this.entitlementChecker = entitlementChecker;
    this.defaultModules = List.copyOf(properties.defaultModules());
  }

  /**
   * Handles one decoded {@code CompanyCreated}, idempotently: binds the event's {@code company_id}
   * as the tenant and grants the DEFAULT module set (persisting {@code tenant_entitlement} rows +
   * one {@code EntitlementGranted} outbox event per grant, all in one transaction). After the
   * grants commit, the company's entitlement-check cache is invalidated so the seeded defaults are
   * immediately visible to a fresh check.
   *
   * @return {@code true} if this delivery granted the defaults (first delivery), {@code false} if
   *     it was a re-delivery skipped by the idempotent consumer.
   */
  public boolean grantDefaultsFor(CompanyCreatedEvent event) {
    boolean granted;
    try {
      granted =
          TenantContext.callAs(
              event.companyId(),
              CONSUMER_ACTOR,
              () -> {
                validateModulesExist(defaultModules);
                return writer.grantDefaults(event, defaultModules);
              });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to grant default entitlements", e);
    }
    if (granted) {
      // After the grants commit, drop the company's cached view so the next check re-reads the
      // committed defaults (read-through re-seeds it). A no-op re-delivery does not re-invalidate.
      entitlementChecker.invalidate(event.companyId());
    }
    return granted;
  }

  /**
   * Grants a module to the bound tenant (the API write path), idempotently by aggregate state.
   * Validates the module exists in the catalog, then persists the grant + emits {@code
   * EntitlementGranted} and invalidates the cache on commit. Granting a module that is ALREADY
   * {@code ENTITLED} is a no-op: it emits no event, leaves the cache untouched, and returns the
   * existing entitlement (the controller maps this to {@code 200}, §1.3).
   *
   * @param moduleKey the module to grant (must exist in {@code module_catalog})
   * @return the persisted (or already-current) entitlement (status {@code ENTITLED}).
   * @throws UnknownModuleException if the module is not in the catalog (→ 400)
   */
  public TenantEntitlement grant(String moduleKey) {
    requireKnownModule(moduleKey);
    String companyId = currentCompanyId();
    // The writer registers the invalidation as an afterCommit synchronization, and only on a real
    // transition — so a rolled-back or no-op (already-ENTITLED) grant never invalidates (FIX 3).
    WriteOutcome outcome = writer.grant(moduleKey, () -> entitlementChecker.invalidate(companyId));
    return outcome.entitlement();
  }

  /**
   * Revokes a module from the bound tenant (the API write path), idempotently by aggregate state.
   * Validates the module exists in the catalog AND that the tenant has an entitlement row for it,
   * flips it to {@code REVOKED} + emits {@code EntitlementRevoked}, and invalidates the cache on
   * commit. A never-granted module is a {@code 404}; a re-revoke of an already-{@code REVOKED} row
   * is a no-op: it emits no event and leaves the cache untouched (§1.3).
   *
   * @param moduleKey the module to revoke
   * @return the persisted (or already-revoked) entitlement (status {@code REVOKED}).
   * @throws UnknownModuleException if the module is not in the catalog (→ 400)
   * @throws EntitlementNotFoundException if the tenant has no entitlement for the module (→ 404)
   */
  public TenantEntitlement revoke(String moduleKey) {
    requireKnownModule(moduleKey);
    TenantEntitlement existing =
        reader
            .findEntitlement(moduleKey)
            .orElseThrow(() -> new EntitlementNotFoundException(moduleKey));
    String companyId = currentCompanyId();
    // Same as grant: invalidation is registered as an afterCommit synchronization by the writer,
    // and
    // only on a real transition — so a rolled-back or no-op (already-REVOKED) revoke never
    // invalidates (FIX 3).
    WriteOutcome outcome = writer.revoke(existing, () -> entitlementChecker.invalidate(companyId));
    return outcome.entitlement();
  }

  /** The bound tenant's entitlements (RLS-scoped), backing {@code GET /api/v1/entitlements}. */
  public List<TenantEntitlement> list() {
    return reader.entitlementsForCurrentTenant();
  }

  private void requireKnownModule(String moduleKey) {
    if (!reader.moduleExists(moduleKey)) {
      throw new UnknownModuleException(moduleKey);
    }
  }

  /**
   * Validates a set of module keys all exist in the catalog (used before granting the configured
   * defaults — an unknown default key is a config error that must fail loudly, not silently grant a
   * phantom module).
   */
  private void validateModulesExist(List<String> moduleKeys) {
    for (String moduleKey : moduleKeys) {
      if (!reader.moduleExists(moduleKey)) {
        throw new UnknownModuleException(moduleKey);
      }
    }
  }

  private static String currentCompanyId() {
    return TenantContext.require().companyId();
  }
}
