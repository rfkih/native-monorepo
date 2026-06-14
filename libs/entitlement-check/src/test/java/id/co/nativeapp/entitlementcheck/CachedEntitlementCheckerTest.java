package id.co.nativeapp.entitlementcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the cache hit / miss / invalidate behaviour against a real Redis.
 *
 * <p>Proves the read-through contract the three gates rely on:
 *
 * <ul>
 *   <li>a MISS consults the loader once, seeds the cache, and a subsequent check is a HIT served
 *       without touching the loader again (both for a positive and a negative answer);
 *   <li>the cache-invalidation entry point ({@link CachedEntitlementChecker#invalidate}) clears the
 *       company's stale cached view so the very next check re-reads the loader — exactly what the
 *       {@code EntitlementGranted}/{@code EntitlementRevoked} listener drives.
 * </ul>
 */
class CachedEntitlementCheckerTest extends RedisTestBase {

  private static final String COMPANY_A = "11111111-1111-1111-1111-111111111111";
  private static final String COMPANY_B = "22222222-2222-2222-2222-222222222222";
  private static final String MODULE = "restaurant";

  /**
   * A loader that records how many times it was consulted and returns whatever its mutable flag
   * currently says — so a test can flip the authoritative answer and prove invalidation re-reads
   * it.
   */
  private static final class CountingLoader implements EntitlementLoader {
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean entitled;

    CountingLoader(boolean entitled) {
      this.entitled = entitled;
    }

    @Override
    public boolean isEntitled(String companyId, String moduleKey) {
      calls.incrementAndGet();
      return entitled;
    }

    int calls() {
      return calls.get();
    }
  }

  @Test
  void aCacheMissConsultsTheLoaderAndASubsequentCheckIsServedFromCache() {
    CountingLoader loader = new CountingLoader(true);
    CachedEntitlementChecker checker = new CachedEntitlementChecker(newCache(), loader);

    // First check: a miss -> the loader is consulted once and the answer is cached.
    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isTrue();
    assertThat(loader.calls()).isEqualTo(1);

    // Second + third checks: hits -> served from Redis, the loader is NOT consulted again.
    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isTrue();
    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isTrue();
    assertThat(loader.calls()).isEqualTo(1);
  }

  @Test
  void aNegativeAnswerIsCachedTooSoARepeatedFalseCheckDoesNotHitTheLoader() {
    CountingLoader loader = new CountingLoader(false);
    CachedEntitlementChecker checker = new CachedEntitlementChecker(newCache(), loader);

    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isFalse();
    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isFalse();
    // The negative answer is cached, so the loader was consulted exactly once.
    assertThat(loader.calls()).isEqualTo(1);
  }

  @Test
  void invalidatingClearsTheStaleEntryAndTheNextCheckRereadsTheLoader() {
    // The company is NOT entitled initially; the answer gets cached as a negative.
    CountingLoader loader = new CountingLoader(false);
    CachedEntitlementChecker checker = new CachedEntitlementChecker(newCache(), loader);

    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isFalse();
    assertThat(loader.calls()).isEqualTo(1);

    // A grant happens at the source of truth (the loader now says entitled) AND its event arrives:
    // the listener invalidates the company's cached view. Without invalidation the stale `false`
    // would still be served; with it, the next check re-reads the loader and sees the grant.
    loader.entitled = true;
    checker.invalidate(COMPANY_A);

    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isTrue();
    assertThat(loader.calls()).isEqualTo(2);

    // And the freshly-seeded positive answer is itself cached again (no third loader call).
    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isTrue();
    assertThat(loader.calls()).isEqualTo(2);
  }

  @Test
  void invalidatingOneCompanyDoesNotDropAnotherCompanysCachedView() {
    CountingLoader loader = new CountingLoader(true);
    CachedEntitlementChecker checker = new CachedEntitlementChecker(newCache(), loader);

    // Seed both companies (two loader calls — one per company on first miss).
    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isTrue();
    assertThat(checker.isEntitled(COMPANY_B, MODULE)).isTrue();
    assertThat(loader.calls()).isEqualTo(2);

    // Invalidating A must not touch B's cached view.
    checker.invalidate(COMPANY_A);

    // B is still a hit (no extra loader call); A re-reads the loader (one more call).
    assertThat(checker.isEntitled(COMPANY_B, MODULE)).isTrue();
    assertThat(loader.calls()).isEqualTo(2);
    assertThat(checker.isEntitled(COMPANY_A, MODULE)).isTrue();
    assertThat(loader.calls()).isEqualTo(3);
  }
}
