package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedListener;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedSchema;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Acceptance criterion (b) — ADR 0010 #13 outbox→Kafka trace continuity, CONSUMER SIDE.
 *
 * <p>Publishes a Kafka record carrying a known W3C {@code traceparent} header (simulating what
 * Debezium emits after the producer stamps the outbox {@code traceparent} column). Asserts that:
 *
 * <ul>
 *   <li>The {@link SaleRecordedListener} listener runs INSIDE a Micrometer span whose
 *       {@code traceId} matches the {@code traceId} extracted from the inbound {@code traceparent}
 *       header — confirming that Spring Kafka observation + the Micrometer tracing bridge restores
 *       the trace context across the Kafka hop.
 *   <li>The existing {@code id}-header idempotency logic is untouched; the test stamps a valid
 *       event id header alongside the traceparent.
 * </ul>
 *
 * <p>The test uses a {@link MockitoSpyBean} on {@link RevenuePostingService} and intercepts
 * {@code handle()} ONLY for the specific event published in this test (matched by {@code saleId})
 * to avoid capturing trace context from unrelated events left in the shared Kafka topic by other
 * test classes that share the same Testcontainers broker.
 *
 * <p>Mirror pattern: this test extends {@link KafkaPostgresTestBase} (a real Testcontainers Kafka
 * broker + PostgreSQL 16, the same infrastructure as {@link SaleRecordedConsumeAcceptanceTest}).
 *
 * <p><strong>Root-cause note:</strong> the {@code TextMapPropagator} bean that wires W3C
 * {@code traceparent} extraction is gated by {@code @ConditionalOnEnabledTracingExport}. If
 * {@code management.tracing.export.enabled=false} is set fleet-wide the propagator bean is not
 * created and every Kafka listener starts a new root span. The {@link
 * id.co.nativeapp.observability.ObservabilityEnvironmentPostProcessor} therefore must NOT set
 * that flag; disabling OTLP span export is achieved by leaving the endpoint unconfigured, which
 * prevents the {@code OtlpTracingConnectionDetails} bean from materialising (ADR 0010 #13).
 */
@SpringBootTest
class TraceContinuityConsumeAcceptanceTest extends KafkaPostgresTestBase {

  /** A well-formed W3C traceparent from a fictional producer span, sampled (flags=01). */
  private static final String PRODUCER_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  /** The traceId extracted from PRODUCER_TRACEPARENT (32-char lowercase hex). */
  private static final String PRODUCER_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private Tracer tracer;

  /** Wraps the real bean so we can intercept {@code handle()} to capture the current span. */
  @MockitoSpyBean private RevenuePostingService postingServiceSpy;

  @BeforeEach
  void verifyTracingInfrastructure() {
    // Confirm the OTel tracing bridge is properly wired (not a no-op). A no-op tracer never
    // returns a non-null span, so the capturedTraceId assertion would trivially pass with null —
    // which would be a false-green. Fail fast here instead.
    assertThat(tracer.getClass().getSimpleName())
        .as("Tracer must be OTel-backed, not NoOp — check MicrometerTracingAutoConfiguration")
        .doesNotContainIgnoringCase("noop");
  }

  @Test
  void listenerSpanIsChildOfProducerSpan_traceIdPropagated() {
    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-06-22T09:00:00Z");

    AtomicReference<String> capturedTraceId = new AtomicReference<>();

    // Intercept ONLY the specific event we publish (matched by saleId).
    // This guards against capturing trace context from unrelated events left in the shared
    // Kafka topic by other test classes that share the same Testcontainers broker.
    Mockito.doAnswer(
            inv -> {
              SaleRecordedEvent event = inv.getArgument(0, SaleRecordedEvent.class);
              if (saleId.equals(event.saleId())) {
                // Capture the active span's traceId at the moment the posting service is called.
                // The listener calls handle() while still inside the Kafka observation scope,
                // so the active span is the one Spring Kafka created from the inbound
                // traceparent header (PropagatingReceiverTracingObservationHandler.onStart()).
                Span current = tracer.currentSpan();
                if (current != null) {
                  capturedTraceId.set(current.context().traceId());
                }
              }
              return inv.callRealMethod();
            })
        .when(postingServiceSpy)
        .handle(Mockito.any());

    // Publish a SaleRecorded record with the known traceparent header.
    publishWithTraceparent(saleId.toString(), eventId, buildEvent(saleId, occurredAt));

    // Await: the spy intercept fires after the listener consumes our specific record.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(capturedTraceId.get())
                    .as(
                        "handle() must have been called for saleId=%s with a span in scope",
                        saleId)
                    .isNotNull());

    // The traceId inside the listener must match the traceId from the inbound traceparent.
    assertThat(capturedTraceId.get())
        .as("listener span traceId must equal the inbound traceparent traceId")
        .isEqualTo(PRODUCER_TRACE_ID);
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static GenericRecord buildEvent(UUID saleId, Instant occurredAt) {
    GenericRecord record = new GenericData.Record(SaleRecordedSchema.schema());
    record.put("sale_id", saleId.toString());
    record.put("company_id", TENANT_A);
    record.put("business_id", BUSINESS_A.toString());
    record.put("amount_minor", 5_000_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", occurredAt.toEpochMilli());
    record.put("tender_type", (Object) null);
    return record;
  }

  private void publishWithTraceparent(String key, UUID eventId, GenericRecord event) {
    byte[] avroBytes = AvroSerde.serialize(event);
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                Base64ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG,
                "all"))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(SaleRecordedSchema.TOPIC, key, avroBytes);
      // Durable event id header: required by the listener's fail-closed check.
      record
          .headers()
          .add(
              SaleRecordedListener.EVENT_ID_HEADER,
              eventId.toString().getBytes(StandardCharsets.UTF_8));
      // W3C traceparent header: this is what Debezium emits from the outbox traceparent column.
      record.headers().add("traceparent", PRODUCER_TRACEPARENT.getBytes(StandardCharsets.UTF_8));
      producer.send(record);
      producer.flush();
    }
  }
}
