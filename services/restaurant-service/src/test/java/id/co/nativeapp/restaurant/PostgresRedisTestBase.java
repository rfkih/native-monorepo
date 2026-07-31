package id.co.nativeapp.restaurant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link PostgresRlsTestBase} PLUS a real Redis 7 — for Phase 6 (ADR 0029) self-order tests that
 * exercise the entitlement-check gate (which hits Redis on every call) but need NO Kafka broker: the
 * entitlement projection is seeded directly via {@code
 * entitlement.service.EntitlementProjectionService#apply} (a synchronous in-process call — no wire
 * needed), exactly mirroring barbershop-service's {@code EntitlementGateFailClosedTest} pattern.
 *
 * <p>Safe to extend {@link PostgresRlsTestBase} here (unlike {@link KafkaPostgresRedisTestBase},
 * which deliberately does NOT — see its javadoc): the parent's {@code @DynamicPropertySource} never
 * sets {@code spring.data.redis.*}, so there is no same-key collision between this class's added
 * property and the parent's — only {@code spring.kafka.bootstrap-servers} is contested fleet-wide,
 * and this base never touches it.
 */
public abstract class PostgresRedisTestBase extends PostgresRlsTestBase {

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  /**
   * Clear the Redis entitlement-check cache between tests so a cached entitled? answer from a prior
   * test cannot leak into the next — the gate must re-read the freshly-truncated projection.
   */
  @BeforeEach
  void flushRedis() {
    try {
      REDIS.execInContainer("redis-cli", "FLUSHALL");
    } catch (Exception ignored) {
      // best-effort; the DB truncate is the primary reset and the cache TTL is a backstop
    }
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }
}
