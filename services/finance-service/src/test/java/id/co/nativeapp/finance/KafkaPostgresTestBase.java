package id.co.nativeapp.finance;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for the end-to-end consume + idempotency tests: a real PostgreSQL 16 (from {@link
 * PostgresRlsTestBase}, as the unprivileged {@code app_user} so RLS engages) PLUS a real Kafka
 * broker via Testcontainers.
 *
 * <p>The end-to-end tests publish a {@code SaleRecorded} Avro message (bytes built with {@code
 * libs/events AvroSerde}) onto the topic; the finance {@code @KafkaListener} consumes it and posts
 * to the ledger. Using a genuine broker (not an embedded mock) exercises the real transport: raw
 * bytes on the wire, the byte[] value deserializer, and the container's offset/ack handling.
 * Awaitility awaits the async consumption (no {@code Thread.sleep}).
 *
 * <p>The broker is the modern KRaft {@code apache/kafka} image (no ZooKeeper). It is a singleton
 * started once and reaped by Ryuk at JVM exit, and its bootstrap servers are wired into Spring via
 * {@code @DynamicPropertySource} so the auto-configured consumer connects to it.
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
