/**
 * The shared <strong>entitlement-check</strong> library — a Redis-cached {@code
 * isEntitled(companyId, moduleKey)} gate used at the three gates ARCHITECTURE.md §2 names (shell
 * render, event ingestion, billing).
 *
 * <p>It is deliberately focused on three things and nothing else:
 *
 * <ul>
 *   <li>the checker contract — {@link id.co.nativeapp.entitlementcheck.EntitlementChecker} (shipped
 *       impl {@link id.co.nativeapp.entitlementcheck.CachedEntitlementChecker});
 *   <li>the per-company Redis cache — {@link id.co.nativeapp.entitlementcheck.EntitlementCache},
 *       which also owns the cache-invalidation entry point ({@code invalidate(companyId)}) a
 *       service calls when an {@code EntitlementGranted}/{@code EntitlementRevoked} event arrives;
 *       and
 *   <li>the loader contract — {@link id.co.nativeapp.entitlementcheck.EntitlementLoader}, the
 *       authoritative fallback consulted on a cache miss (DB-backed in the owning
 *       entitlement-service, a read-model/projection lookup in a vertical).
 * </ul>
 *
 * <p>It owns no storage of its own beyond the cache: the source of truth lives in whichever service
 * supplies the loader. See ARCHITECTURE.md §2 (entitlement-service) and CLAUDE.md.
 */
package id.co.nativeapp.entitlementcheck;
