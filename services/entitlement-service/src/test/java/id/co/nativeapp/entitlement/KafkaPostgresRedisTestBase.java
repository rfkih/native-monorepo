package id.co.nativeapp.entitlement;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for the end-to-end consume + entitlement-check tests: a real PostgreSQL 16 (from {@link
 * PostgresRlsTestBase}, as the unprivileged {@code app_user} so RLS engages), a real Kafka broker,
 * and a real Redis 7 (the entitlement-check cache).
 *
 * <p>The end-to-end tests publish a {@code CompanyCreated} Avro message (bytes built with {@code
 * libs/events AvroSerde}) onto the topic; the entitlement {@code @KafkaListener} consumes it and
 * grants the company its default entitlements. Using genuine collaborators (not mocks) exercises
 * the real transport: raw bytes on the wire, the byte[] value deserializer, the RLS-scoped grants,
 * the outbox writes, and the Redis cache invalidation. Awaitility awaits the async consumption (no
 * {@code Thread.sleep}).
 *
 * <p>Kafka is the modern KRaft {@code apache/kafka} image (no ZooKeeper); Redis is a plain {@code
 * redis:7-alpine} GenericContainer from the core Testcontainers module. Both are singletons started
 * once and reaped by Ryuk at JVM exit, and their endpoints are wired into Spring via
 * {@code @DynamicPropertySource}.
 */
abstract class KafkaPostgresRedisTestBase extends PostgresRlsTestBase {

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    KAFKA.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void kafkaAndRedisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }
}
