package id.co.nativeapp.restaurant.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.entitlementcheck.EntitlementChecker;
import id.co.nativeapp.restaurant.EventFixtures;
import id.co.nativeapp.restaurant.KafkaPostgresRedisTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The entitlement gate, driven end-to-end through Kafka (Phase 6, ADR 0029) — restaurant-service's
 * first entitlement-gated feature. Mirrors barbershop-service's/carwash-service's {@code
 * EntitlementGateConsumeTest} exactly, adapted to probe the shared {@link EntitlementChecker} bean
 * directly (rather than a specific write path — self-order's create path needs BOTH a real menu
 * item AND a filter-bound {@code SelfOrderPrincipal}, so the checker itself is the more focused
 * seam for proving the WIRE consumption; the full create-path gate semantics — 403/429/side-effects
 * — are proven in {@code selforder.SelfOrderCreateGateTest}).
 *
 * <p>Publishing an {@code EntitlementGranted} for {@code self_order} — the way entitlement-service
 * + Debezium would — is consumed into the local {@code entitlement_projection} and INVALIDATES the
 * entitlement-check Redis cache, so a previously-false answer flips to {@code true}. Publishing an
 * {@code EntitlementRevoked} flips it back. Awaitility awaits the async consumption (no {@code
 * Thread.sleep}).
 */
@SpringBootTest
class EntitlementGateConsumeTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String SELF_ORDER = "self_order";

  @Autowired private EntitlementChecker entitlementChecker;
  @Autowired private StringRedisTemplate redis;

  @Test
  void grantOpensTheGateAndRevokeClosesItWithTheCacheInvalidated() {
    // Before any grant: not entitled, and that negative answer gets cached.
    assertThat(entitlementChecker.isEntitled(TENANT_A, SELF_ORDER)).isFalse();
    assertThat(cachedField(TENANT_A, SELF_ORDER)).isEqualTo("0");

    // Publish EntitlementGranted (raw Avro bytes + id header) — entitlement-service + Debezium
    // shape.
    EventFixtures.publishEntitlementGranted(
        KAFKA.getBootstrapServers(),
        TENANT_A,
        UUID.randomUUID(),
        EventFixtures.entitlementGranted(TENANT_A, SELF_ORDER));

    // The consumer applies the grant and invalidates the company's cache; the checker flips to
    // true. Awaitility polls (the consume is async).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(entitlementChecker.isEntitled(TENANT_A, SELF_ORDER)).isTrue());

    // Publish EntitlementRevoked — the consumer flips the projection + invalidates the cache
    // again.
    EventFixtures.publishEntitlementRevoked(
        KAFKA.getBootstrapServers(),
        TENANT_A,
        UUID.randomUUID(),
        EventFixtures.entitlementRevoked(TENANT_A, SELF_ORDER));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(entitlementChecker.isEntitled(TENANT_A, SELF_ORDER)).isFalse());
  }

  /** The cached hash field for a (company, module), or null if not cached. */
  private String cachedField(String companyId, String moduleKey) {
    return redis.<String, String>opsForHash().get("entitlement:cache:" + companyId, moduleKey);
  }
}
