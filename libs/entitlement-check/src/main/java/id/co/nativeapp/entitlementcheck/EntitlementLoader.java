package id.co.nativeapp.entitlementcheck;

/**
 * The source-of-truth fallback the {@link EntitlementChecker} consults on a cache miss.
 *
 * <p>This library deliberately owns NO storage: it is the cache + the checker contract + the
 * cache-invalidation entry point, nothing more (ARCHITECTURE.md §2). The authoritative "is this
 * company entitled to this module?" answer lives elsewhere, and WHERE depends on who consumes the
 * lib:
 *
 * <ul>
 *   <li>the <strong>owning entitlement-service</strong> supplies a DB-backed loader that queries
 *       its own {@code tenant_entitlement} table (an {@code ENTITLED} row exists); and
 *   <li>a <strong>vertical consuming the lib</strong> supplies a read-model / projection loader
 *       that checks its locally-cached entitlement projection (kept current by {@code
 *       EntitlementGranted/Revoked} events).
 * </ul>
 *
 * <p>A loader is consulted only on a cache MISS — a hit is served straight from Redis — so it must
 * be authoritative but need not be fast. It returns a plain {@code boolean} so the lib stays free
 * of any persistence type; the implementation runs whatever query (RLS-scoped JPA, projection
 * lookup, etc.) its owner deems correct.
 */
@FunctionalInterface
public interface EntitlementLoader {

  /**
   * The authoritative entitlement answer for one {@code (company, module)} pair, consulted on a
   * cache miss.
   *
   * @param companyId the owning tenant (UUID as string), never null/blank
   * @param moduleKey the module key (e.g. {@code "restaurant"}, {@code "finance"}), never
   *     null/blank
   * @return {@code true} if the company is currently entitled to the module
   */
  boolean isEntitled(String companyId, String moduleKey);
}
