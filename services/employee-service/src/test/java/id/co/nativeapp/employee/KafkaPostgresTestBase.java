package id.co.nativeapp.employee;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for the end-to-end consume + AssignmentChanged-emission tests: a real PostgreSQL 16 (from
 * {@link PostgresRlsTestBase}, as the unprivileged {@code app_user} so RLS engages) PLUS a real
 * Kafka broker via Testcontainers.
 *
 * <p>The consume tests publish an {@code OrgUnitCreated}/{@code OrgUnitChanged} Avro message (bytes
 * built via {@code libs/events AvroSerde}) onto the topic; the employee {@code @KafkaListener}
 * consumes it and updates the local org read model. Using a genuine broker (not an embedded mock)
 * exercises the real transport: raw bytes on the wire, the {@code byte[]} value deserializer, and
 * the container's offset/ack handling. Awaitility awaits the async consumption (no {@code
 * Thread.sleep}).
 *
 * <p>The broker is the modern KRaft {@code apache/kafka} image (no ZooKeeper). It is a singleton
 * started once and reaped by Ryuk at JVM exit; its bootstrap servers are wired into Spring via
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
