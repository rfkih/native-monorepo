package id.co.nativeapp.entitlementcheck;

/**
 * The shared entitlement-check contract used at the three gates ARCHITECTURE.md §2 names — shell
 * render, event ingestion, and billing: {@code isEntitled(companyId, moduleKey)}.
 *
 * <p>The answer is served from a per-company Redis read-through cache and falls back to a supplied
 * {@link EntitlementLoader} on a cache miss; the cache is invalidated by the entitlement-service's
 * {@code EntitlementGranted}/{@code EntitlementRevoked} events (via {@link
 * EntitlementCache#invalidate}). This keeps a hot entitlement gate off the database/projection on
 * the common path while staying promptly consistent with grants/revokes.
 *
 * <p>It is an interface so a gate can depend on the contract (and a test can stub it); the shipped
 * implementation is {@link CachedEntitlementChecker}.
 */
public interface EntitlementChecker {

  /**
   * Is the given company currently entitled to the given module?
   *
   * @param companyId the owning tenant (UUID as string)
   * @param moduleKey the module key (e.g. {@code "restaurant"}, {@code "finance"})
   * @return {@code true} if the company is entitled to the module right now
   */
  boolean isEntitled(String companyId, String moduleKey);
}
