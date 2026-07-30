package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.domain.NotEntitledException;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Acceptance (c) — the entitlement gate, driven end-to-end through Kafka. Ported from
 * carwash-service's {@code EntitlementGateConsumeTest} (ADR 0024).
 *
 * <p>Before any grant, writing a catalog service is rejected with {@code 403} ({@link
 * NotEntitledException}). Publishing an {@code EntitlementGranted} for the barbershop module — the
 * way entitlement-service + Debezium would — is consumed into the local entitlement projection and
 * INVALIDATES the entitlement-check Redis cache, so the previously-403 write now SUCCEEDS.
 * Publishing an {@code EntitlementRevoked} flips the projection back and again invalidates the
 * cache, so the write is rejected with {@code 403} once more.
 *
 * <p><strong>Deliberate difference from carwash-service.</strong> carwash's equivalent test probes
 * the gate through {@code WashService.recordWash} — the vertical's one legacy write path. There is
 * no such analog here (ADR 0024: the ticket checkout is the ONLY revenue path, and it requires
 * pre-existing catalog + staff-profile rows the gate itself would block creating before a grant).
 * This test instead probes the gate through {@link CatalogService#createService} — a write that is
 * gated by the IDENTICAL {@code requireEntitled()} pattern (see {@code CatalogService} /{@code
 * TicketService} javadoc) and needs no pre-existing catalog state of its own, so the Kafka-driven
 * grant/revoke cycle can be exercised standalone. The full revenue-path fail-closed proof (with
 * {@code SaleRecorded} emission) lives in {@link EntitlementGateFailClosedTest}.
 *
 * <p>This is the Phase-2 "real gating in the verticals": the gate reflects grants/revokes promptly
 * because the consumer drops the company's cached view on apply. Awaitility awaits the async
 * consumption (no {@code Thread.sleep}).
 */
@SpringBootTest
class EntitlementGateConsumeTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final String BARBERSHOP = "barbershop";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private CatalogService catalogService;
  @Autowired private StringRedisTemplate redis;

  @Test
  void grantOpensTheGateAndRevokeClosesItWithTheCacheInvalidated() {
    // Before any grant: the gate is closed -> 403, and that negative answer is cached.
    assertServiceCreateRejected("k-before");
    assertThat(cachedField(TENANT_A, BARBERSHOP)).isEqualTo("0"); // a stale "0" now sits in cache

    // Publish EntitlementGranted (raw Avro bytes + id header) — entitlement-service + Debezium
    // shape.
    EventFixtures.publishEntitlementGranted(
        KAFKA.getBootstrapServers(),
        TENANT_A,
        UUID.randomUUID(),
        EventFixtures.entitlementGranted(TENANT_A, BARBERSHOP));

    // The consumer applies the grant and invalidates the company's cache; the gate opens — a
    // service create now succeeds. Awaitility polls (the consume is async) until the
    // previously-403 write is allowed.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .ignoreException(NotEntitledException.class)
        .untilAsserted(() -> assertServiceCreated("k-after-grant"));

    // Publish EntitlementRevoked — the consumer flips the projection + invalidates the cache
    // again.
    EventFixtures.publishEntitlementRevoked(
        KAFKA.getBootstrapServers(),
        TENANT_A,
        UUID.randomUUID(),
        EventFixtures.entitlementRevoked(TENANT_A, BARBERSHOP));

    // The gate closes again — a write is rejected with 403. Await the async revoke to take
    // effect.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> createService("k-after-revoke"))
                    .isInstanceOf(NotEntitledException.class));
  }

  private void assertServiceCreateRejected(String name) {
    assertThatThrownBy(() -> createService(name)).isInstanceOf(NotEntitledException.class);
  }

  private void assertServiceCreated(String name) throws Exception {
    assertThat(createService(name)).isNotNull();
  }

  private CatalogItemResponse createService(String name) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createService(
                new CatalogItemCreateRequest(OUTLET, name, null, 30_000_00L, "IDR", null)));
  }

  /** The cached hash field for a (company, module), or null if not cached. */
  private String cachedField(String companyId, String moduleKey) {
    return redis.<String, String>opsForHash().get("entitlement:cache:" + companyId, moduleKey);
  }
}
