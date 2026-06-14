package id.co.nativeapp.entitlementcheck;

import java.util.Objects;
import java.util.Optional;

/**
 * The shipped {@link EntitlementChecker}: a Redis read-through cache ({@link EntitlementCache}) in
 * front of a supplied {@link EntitlementLoader}.
 *
 * <p><strong>Read-through.</strong> {@link #isEntitled(String, String)} first looks in the
 * per-company cache; on a hit it returns the cached answer (both positive and negative answers are
 * cached), and on a miss it consults the authoritative loader, stores the result, and returns it.
 * So the common path is a single Redis read, and the loader (an RLS-scoped DB query in the owning
 * service, or a projection lookup in a vertical) is consulted only when nothing is cached yet or
 * the company's cache was just invalidated by an event.
 *
 * <p><strong>Consistency comes from invalidation, not TTL.</strong> When an {@code
 * EntitlementGranted}/{@code EntitlementRevoked} event arrives, the consuming service calls {@link
 * EntitlementCache#invalidate(String)} (exposed via {@link #invalidate(String)}), dropping the
 * company's whole cached view; the next check re-reads the loader and re-seeds the cache. The cache
 * TTL is only a safety net behind that.
 *
 * <p>The {@link EntitlementCache} is the single collaborator that owns the invalidation entry
 * point, but this checker re-exposes {@link #invalidate(String)} so a consuming service can hold
 * one bean (the checker) and both query and invalidate through it.
 */
public class CachedEntitlementChecker implements EntitlementChecker {

  private final EntitlementCache cache;
  private final EntitlementLoader loader;

  /**
   * @param cache the per-company Redis cache (lookup/store/invalidate)
   * @param loader the authoritative fallback consulted on a cache miss (DB-backed in the owning
   *     service, projection-backed in a vertical)
   */
  public CachedEntitlementChecker(EntitlementCache cache, EntitlementLoader loader) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.loader = Objects.requireNonNull(loader, "loader");
  }

  @Override
  public boolean isEntitled(String companyId, String moduleKey) {
    requireNonBlank(companyId, "companyId");
    requireNonBlank(moduleKey, "moduleKey");

    Optional<Boolean> cached = cache.lookup(companyId, moduleKey);
    if (cached.isPresent()) {
      return cached.get();
    }
    // Cache miss: consult the authoritative loader, seed the cache, return the answer.
    boolean entitled = loader.isEntitled(companyId, moduleKey);
    cache.store(companyId, moduleKey, entitled);
    return entitled;
  }

  /**
   * The cache-invalidation entry point the consuming service calls from its {@code
   * EntitlementGranted}/{@code EntitlementRevoked} listener — drops the company's whole cached view
   * so the next check re-reads the loader. Delegates to {@link
   * EntitlementCache#invalidate(String)}.
   *
   * @param companyId the company whose cached entitlement view to drop
   */
  public void invalidate(String companyId) {
    cache.invalidate(companyId);
  }

  private static void requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
