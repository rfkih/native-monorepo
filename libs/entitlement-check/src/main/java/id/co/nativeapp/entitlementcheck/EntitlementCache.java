package id.co.nativeapp.entitlementcheck;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The per-company Redis cache behind {@link EntitlementChecker}, plus the cache-invalidation entry
 * point a consuming service calls when an {@code EntitlementGranted}/{@code EntitlementRevoked}
 * event arrives.
 *
 * <p><strong>Keyed by company (a hash per company).</strong> Each company's entitlement answers are
 * stored in ONE Redis hash, {@code entitlement:cache:&lt;companyId&gt;}, whose fields are module
 * keys and whose values are {@code "1"} (entitled) / {@code "0"} (not). This shape is what makes
 * invalidation a single, cheap {@code DEL} of the company's hash when an event for that company
 * arrives (ARCHITECTURE.md §2: "Entitlement state is cached in Redis and invalidated by its
 * events") — there is no need to know which exact module changed to drop the company's stale view.
 *
 * <p><strong>Caches both answers.</strong> A negative result ({@code not entitled}) is cached too,
 * so a hot "is this company entitled to X?" check that is repeatedly false does not hit the loader
 * every time; the event-driven invalidation makes a later grant visible promptly (the stale {@code
 * 0} is cleared by the {@code EntitlementGranted} event). Entries also carry a bounded TTL as a
 * safety net so a missed invalidation cannot pin a stale answer forever.
 *
 * <p>This class is intentionally storage-only: it knows nothing about the authoritative source
 * (that is the {@link EntitlementLoader}); it only reads/writes/clears the cached view.
 */
public class EntitlementCache {

  /** Redis key prefix for a company's entitlement hash: {@code entitlement:cache:<companyId>}. */
  static final String KEY_PREFIX = "entitlement:cache:";

  /** Hash-field value for an entitled module. */
  static final String ENTITLED = "1";

  /** Hash-field value for a non-entitled module. */
  static final String NOT_ENTITLED = "0";

  /**
   * TTL safety net: even if an invalidation event were somehow missed, a cached entry self-expires,
   * so a stale answer cannot be pinned indefinitely. Invalidation by event is the primary
   * mechanism; this is only the backstop.
   */
  private final Duration ttl;

  private final StringRedisTemplate redis;

  /**
   * @param redis the Redis template the cache reads/writes through (Lettuce-backed)
   * @param ttl the per-company entry TTL safety net; must be positive
   */
  public EntitlementCache(StringRedisTemplate redis, Duration ttl) {
    this.redis = Objects.requireNonNull(redis, "redis");
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    this.ttl = ttl;
  }

  /**
   * The cached entitlement answer for a {@code (company, module)} pair.
   *
   * @return {@code Optional.of(true|false)} on a cache hit (both positive and negative answers are
   *     cached), or {@code Optional.empty()} on a miss — the caller then consults the loader.
   */
  public Optional<Boolean> lookup(String companyId, String moduleKey) {
    String cached = redis.<String, String>opsForHash().get(keyFor(companyId), moduleKey);
    if (cached == null) {
      return Optional.empty();
    }
    return Optional.of(ENTITLED.equals(cached));
  }

  /**
   * Records the authoritative answer for a {@code (company, module)} pair so the next check is a
   * cache hit. (Re)sets the company hash's TTL safety net on every write.
   */
  public void store(String companyId, String moduleKey, boolean entitled) {
    String key = keyFor(companyId);
    redis.opsForHash().put(key, moduleKey, entitled ? ENTITLED : NOT_ENTITLED);
    redis.expire(key, ttl);
  }

  /**
   * The cache-invalidation entry point: drops a company's ENTIRE cached entitlement view in a
   * single {@code DEL}. A consuming service calls this from its {@code EntitlementGranted}/{@code
   * EntitlementRevoked} listener (the event carries {@code company_id}); the next check for any of
   * that company's modules re-reads the authoritative loader and re-seeds the cache. Dropping the
   * whole company hash (rather than one field) is correct and cheap: an entitlement change for a
   * company makes its cached view stale, and a fresh read is the safe response.
   */
  public void invalidate(String companyId) {
    redis.delete(keyFor(companyId));
  }

  private static String keyFor(String companyId) {
    Objects.requireNonNull(companyId, "companyId");
    if (companyId.isBlank()) {
      throw new IllegalArgumentException("companyId must not be blank");
    }
    return KEY_PREFIX + companyId;
  }
}
