package id.co.nativeapp.loyalty;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for the end-to-end consume tests: a real PostgreSQL 16 (from {@link PostgresRlsTestBase}, as
 * the unprivileged {@code app_user} so RLS engages) + a real Kafka broker.
 *
 * <p><strong>Deviation from the vertical-service template (documented).</strong> The barbershop-
 * service recipe this scaffold otherwise mirrors names this class {@code
 * KafkaPostgresRedisTestBase} and starts a Redis container (the {@code libs/entitlement-check}
 * cache). loyalty-service deliberately has NO {@code libs/entitlement-check} dependency (not
 * module-gated this phase — see the build.gradle.kts header), so there is nothing to point a Redis
 * container at; this base is named {@code KafkaPostgresTestBase} and omits Redis entirely rather
 * than start an unused container.
 *
 * <p>The end-to-end tests publish Avro messages (bytes built with {@code libs/events AvroSerde})
 * onto the topics; the loyalty {@code @KafkaListener}s consume them into the ledger. Using a
 * genuine broker (not a mock) exercises the real transport: raw bytes on the wire, the byte[] value
 * deserializer, and the RLS-scoped ledger writes. Awaitility awaits the async consumption (no
 * {@code Thread.sleep}).
 *
 * <p>Kafka is the modern KRaft {@code apache/kafka} image (no ZooKeeper) — a singleton started once
 * and reaped by Ryuk at JVM exit, its endpoint wired into Spring via {@code @DynamicPropertySource}.
 */
abstract class KafkaPostgresTestBase extends PostgresRlsTestBase {

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

  static {
    KAFKA.start();
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }
}
