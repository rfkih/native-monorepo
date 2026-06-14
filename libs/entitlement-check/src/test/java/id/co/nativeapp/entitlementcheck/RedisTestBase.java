package id.co.nativeapp.entitlementcheck;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers Redis base for the entitlement-cache tests.
 *
 * <p>Redis cache hit/miss/invalidate is a Redis behaviour an in-JVM map cannot prove (the
 * cache-invalidation entry point must actually clear the stored hash so a stale entry is gone), so
 * these tests run against a REAL {@code redis:7-alpine} via a plain Testcontainers {@link
 * GenericContainer} from the core module — the same pattern the gateway uses for its token bucket.
 * The container is a singleton started once and reaped by Ryuk at JVM exit; each test starts from a
 * flushed keyspace so the cases are order-independent.
 */
abstract class RedisTestBase {

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  /** A short TTL for the cache safety net in tests — invalidation, not expiry, is the proof. */
  static final Duration TTL = Duration.ofMinutes(5);

  private StringRedisTemplate redisTemplate;

  StringRedisTemplate redisTemplate() {
    return redisTemplate;
  }

  EntitlementCache newCache() {
    return new EntitlementCache(redisTemplate, TTL);
  }

  @BeforeEach
  void connectAndFlush() {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    this.redisTemplate = new StringRedisTemplate(factory);
    this.redisTemplate.afterPropertiesSet();
    flushAll(factory);
  }

  private static void flushAll(RedisConnectionFactory factory) {
    factory.getConnection().serverCommands().flushAll();
  }
}
