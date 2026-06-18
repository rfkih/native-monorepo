package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.carwash.wash.domain.NotEntitledException;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.service.WashService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Acceptance (c) — the entitlement gate, driven end-to-end through Kafka.
 *
 * <p>Before any grant, recording a wash is rejected with {@code 403} ({@link
 * NotEntitledException}). Publishing an {@code EntitlementGranted} for the carwash module — the way
 * entitlement-service + Debezium would — is consumed into the local entitlement projection and
 * INVALIDATES the entitlement-check Redis cache, so the previously-403 wash now SUCCEEDS.
 * Publishing an {@code EntitlementRevoked} flips the projection back and again invalidates the
 * cache, so the wash is rejected with {@code 403} once more.
 *
 * <p>This is the Phase-2 "real gating in the verticals": the gate reflects grants/revokes promptly
 * because the consumer drops the company's cached view on apply. Awaitility awaits the async
 * consumption (no {@code Thread.sleep}).
 */
@SpringBootTest
class EntitlementGateConsumeTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final String CARWASH = "carwash";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private WashService washService;
  @Autowired private StringRedisTemplate redis;

  @Test
  void grantOpensTheGateAndRevokeClosesItWithTheCacheInvalidated() {
    // Before any grant: the gate is closed -> 403, and that negative answer is cached.
    assertWashRejected("k-before");
    assertThat(cachedField(TENANT_A, CARWASH)).isEqualTo("0"); // a stale "0" now sits in the cache

    // Publish EntitlementGranted (raw Avro bytes + id header) — entitlement-service + Debezium
    // shape.
    EventFixtures.publishEntitlementGranted(
        KAFKA.getBootstrapServers(),
        TENANT_A,
        UUID.randomUUID(),
        EventFixtures.entitlementGranted(TENANT_A, CARWASH));

    // The consumer applies the grant and invalidates the company's cache; the gate opens — a wash
    // now succeeds. Awaitility polls (the consume is async) until the previously-403 wash is
    // allowed.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .ignoreException(NotEntitledException.class)
        .untilAsserted(() -> assertWashRecorded("k-after-grant"));

    // Publish EntitlementRevoked — the consumer flips the projection + invalidates the cache again.
    EventFixtures.publishEntitlementRevoked(
        KAFKA.getBootstrapServers(),
        TENANT_A,
        UUID.randomUUID(),
        EventFixtures.entitlementRevoked(TENANT_A, CARWASH));

    // The gate closes again — a wash is rejected with 403. Await the async revoke to take effect.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> recordWash("k-after-revoke"))
                    .isInstanceOf(NotEntitledException.class));
  }

  private void assertWashRejected(String idempotencyKey) {
    assertThatThrownBy(() -> recordWash(idempotencyKey)).isInstanceOf(NotEntitledException.class);
  }

  private void assertWashRecorded(String idempotencyKey) throws Exception {
    assertThat(recordWash(idempotencyKey)).isNotNull();
  }

  private UUID recordWash(String idempotencyKey) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            washService
                .recordWash(
                    new RecordWashCommand(
                        OUTLET,
                        "bay-1",
                        5_000_00L,
                        "IDR",
                        null,
                        null,
                        Instant.parse("2026-06-14T08:30:00Z"),
                        idempotencyKey))
                .wash()
                .id());
  }

  /** The cached hash field for a (company, module), or null if not cached. */
  private String cachedField(String companyId, String moduleKey) {
    return redis.<String, String>opsForHash().get("entitlement:cache:" + companyId, moduleKey);
  }
}
