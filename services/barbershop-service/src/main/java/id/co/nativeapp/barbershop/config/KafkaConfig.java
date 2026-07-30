package id.co.nativeapp.barbershop.config;

import id.co.nativeapp.errorinbox.ConsumeErrorRecorder;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer wiring for barbershop-service's listeners — the {@code EntitlementGranted}/{@code
 * EntitlementRevoked} entitlement-projection consumer and the {@code EmployeeChanged}/{@code
 * AssignmentChanged} staff-read-model consumer — the same shape entitlement-service /
 * employee-service / finance-service / carwash-service use.
 *
 * <p><strong>Raw Avro bytes, no Schema Registry.</strong> The topic value is the producer outbox
 * payload (raw Avro bytes shipped by Debezium), so the value deserializer is a plain {@code
 * ByteArrayDeserializer} (configured in {@code application.yml}); each listener decodes the bytes
 * itself with {@code libs/events AvroSerde} against this service's own consumer copy of the schema.
 * We deliberately do NOT use a Confluent kafka-avro-serializer / Schema Registry serde.
 *
 * <p><strong>Resilience (ENGINEERING-STANDARDS §4): bounded retries then DLT.</strong> A
 * non-transient failure is retried a bounded number of times with a fixed backoff and then routed
 * to {@code <topic>.DLT} via a {@link DeadLetterPublishingRecoverer}, so a poison record cannot
 * stall the consumer. The two deterministic poison failures ({@link EventDecodeException}, {@link
 * MissingEventIdException}) are classified non-retryable so they are DLT'd immediately rather than
 * wasting the retry budget. Idempotency is by event UUID (the {@code ProcessedEventStore}), so the
 * at-least-once redelivery these retries cause never double-applies.
 */
@Configuration
public class KafkaConfig {

  /**
   * Bounded retry budget before a record is sent to the DLT (never an unbounded in-place retry).
   */
  private static final long RETRY_INTERVAL_MS = 500L;

  private static final long MAX_RETRIES = 3L;

  /** The dead-letter topic suffix — a poison record goes to {@code <topic>.DLT}. */
  static final String DLT_SUFFIX = ".DLT";

  /**
   * The typed listener container factory the barbershop {@code @KafkaListener}s bind to: keys are
   * {@link String}, values are {@code byte[]} (raw Avro). Built from the Boot-bound {@link
   * KafkaProperties} so the {@code application.yml} consumer config applies, with the DLT error
   * handler attached.
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
      KafkaProperties kafkaProperties, DefaultErrorHandler kafkaErrorHandler) {
    ConsumerFactory<String, byte[]> consumerFactory =
        new DefaultKafkaConsumerFactory<>(kafkaProperties.buildConsumerProperties());
    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(kafkaErrorHandler);
    // ADR 0010 #13 — outbox→Kafka trace continuity: enable Micrometer observation on this custom
    // container factory so Spring Kafka extracts the incoming W3C `traceparent` Kafka header and
    // makes the listener span a child of the producer span. The global
    // spring.kafka.listener.observation-enabled property only applies to Spring Boot's
    // auto-configured factory; custom factories require this explicit call.
    factory.getContainerProperties().setObservationEnabled(true);
    return factory;
  }

  /**
   * Bounded-retry error handler that routes a poison record to {@code <topic>.DLT} after the retry
   * budget is exhausted. The deterministic poison failures are classified non-retryable so they are
   * DLT'd immediately.
   *
   * <p><strong>Error-inbox wiring (ADR 0005/0009).</strong> Before the {@link
   * DeadLetterPublishingRecoverer} publishes to the DLT, a {@link ConsumerRecordRecoverer} wrapper
   * calls {@link ConsumeErrorRecorder#record} to upsert a deduplicated, PII-redacted row into the
   * local {@code error_log} table (written via a {@code REQUIRES_NEW} JdbcTemplate upsert so the
   * record survives a rolled-back business transaction). The {@link
   * id.co.nativeapp.errorinbox.AlertWebhookClient} configured by {@code libs/error-inbox}
   * auto-registration fires the webhook alert from that same call. Mirrors the wiring in
   * carwash-service's/finance-service's {@code KafkaConfig}.
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
      ConsumeErrorRecorder consumeErrorRecorder) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            deadLetterKafkaTemplate,
            (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
    ConsumerRecordRecoverer wrapped =
        (rec, ex) -> {
          consumeErrorRecorder.record(rec, ex);
          recoverer.accept(rec, ex);
        };
    DefaultErrorHandler handler =
        new DefaultErrorHandler(wrapped, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    handler.addNotRetryableExceptions(
        UncheckedIOException.class, EventDecodeException.class, MissingEventIdException.class);
    return handler;
  }

  /**
   * The {@link KafkaTemplate} the DLT recoverer publishes with. After the consumer's {@link
   * id.co.nativeapp.events.Base64ByteArrayDeserializer} runs, the original record value is the RAW
   * Avro {@code byte[]}, so re-publishing to the DLT must use the SAME base64 transport encoding as
   * the source topic — a {@link StringSerializer} key and a {@link Base64ByteArraySerializer} value
   * — overriding the auto-configured template's String/String defaults (which would throw {@code
   * ClassCastException: [B cannot be cast to String} on every DLT publish and leave the poison
   * record looping on the partition).
   */
  @Bean
  public KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(KafkaProperties kafkaProperties) {
    Map<String, Object> producerProps = new HashMap<>(kafkaProperties.buildProducerProperties());
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Base64ByteArraySerializer.class);
    ProducerFactory<String, byte[]> producerFactory =
        new DefaultKafkaProducerFactory<>(producerProps);
    return new KafkaTemplate<>(producerFactory);
  }
}
