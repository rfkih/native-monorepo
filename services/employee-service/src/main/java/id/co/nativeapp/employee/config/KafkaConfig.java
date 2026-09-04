package id.co.nativeapp.employee.config;

import id.co.nativeapp.employee.org.messaging.MissingEventIdException;
import id.co.nativeapp.employee.org.messaging.OrgUnitDecodeException;
import id.co.nativeapp.employee.payroll.messaging.MissingPayrollEventIdException;
import id.co.nativeapp.employee.payroll.messaging.PayrollEventDecodeException;
import id.co.nativeapp.errorinbox.ConsumeErrorRecorder;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
 * Kafka consumer wiring for the {@code OrgUnitCreated} / {@code OrgUnitChanged} listener — the same
 * shape finance-service uses for {@code SaleRecorded} and entitlement-service for {@code
 * CompanyCreated}.
 *
 * <p><strong>Raw Avro bytes, no Schema Registry.</strong> The topic value is the org-service outbox
 * payload (raw Avro bytes shipped by Debezium), so the value deserializer is a plain {@code
 * ByteArrayDeserializer} (configured in {@code application.yml}); the listener decodes the bytes
 * itself with {@code libs/events AvroSerde} against employee-service's own consumer copies of the
 * schemas. We deliberately do NOT use a Confluent kafka-avro-serializer / Schema Registry serde.
 *
 * <p><strong>Resilience (ENGINEERING-STANDARDS §4): bounded retries then DLT.</strong> A
 * non-transient failure is retried a bounded number of times with a fixed backoff and then routed
 * to {@code <topic>.DLT} via a {@link DeadLetterPublishingRecoverer}, so a poison record cannot
 * stall the consumer / block the partition. Two deterministic poison failures ({@link
 * OrgUnitDecodeException}, {@link MissingEventIdException}) are classified non-retryable so they
 * are DLT'd immediately rather than wasting the retry budget. Idempotency is by event UUID (the
 * {@code ProcessedEventStore}), so the at-least-once redelivery these retries cause never
 * double-applies an org-tree update.
 */
@Configuration
public class KafkaConfig {

  /**
   * Bounded retry budget before a record is sent to the DLT (never an unbounded in-place retry).
   */
  private static final long RETRY_INTERVAL_MS = 500L;

  private static final long MAX_RETRIES = 3L;

  /**
   * The dead-letter topic suffix — a poison {@code OrgUnitChanged} goes to {@code
   * OrgUnitChanged.DLT}.
   */
  static final String DLT_SUFFIX = ".DLT";

  /**
   * The typed listener container factory the org-event {@code @KafkaListener}s bind to: keys are
   * {@link String}, values are {@code byte[]} (raw Avro). Built from the Boot-bound {@link
   * KafkaProperties} so the {@code application.yml} consumer config (bootstrap servers, group id,
   * offset reset, deserializers, ack mode) applies, with the DLT error handler attached.
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
   * The consumer group for the {@code CompanyCreated} auto-bootstrap listener — deliberately
   * SEPARATE from the main {@code employee-service} group and pinned to {@code
   * auto.offset.reset=latest}.
   *
   * <p><strong>Why a dedicated group + latest.</strong> The main group resets to {@code earliest}
   * so a fresh consumer hydrates its org read model from history. If the {@code CompanyCreated}
   * listener joined that group, enabling it would REPLAY every historical {@code CompanyCreated}
   * and mass-activate official payroll for every existing tenant at once (an implicit,
   * hard-to-reverse bulk mutation that could also supersede a tenant's hand-verified statutory
   * override). Pinning it to {@code latest} in its own group makes enabling it strictly
   * FORWARD-ONLY: only companies created AFTER it starts are auto-bootstrapped. Existing tenants
   * are activated deliberately via the console payroll setup-gate ({@code POST
   * /api/v1/payroll-setup/seed-official-bootstrap}).
   */
  static final String BOOTSTRAP_GROUP_ID = "employee-payroll-bootstrap";

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, byte[]>
      companyBootstrapListenerContainerFactory(
          KafkaProperties kafkaProperties, DefaultErrorHandler kafkaErrorHandler) {
    Map<String, Object> consumerProps = kafkaProperties.buildConsumerProperties();
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, BOOTSTRAP_GROUP_ID);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    ConsumerFactory<String, byte[]> consumerFactory =
        new DefaultKafkaConsumerFactory<>(consumerProps);
    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(kafkaErrorHandler);
    factory.getContainerProperties().setObservationEnabled(true);
    return factory;
  }

  /**
   * Bounded-retry error handler that routes a poison record to {@code <topic>.DLT} after the retry
   * budget is exhausted. The two deterministic poison failures are classified non-retryable so they
   * are DLT'd immediately.
   *
   * <p><strong>Error-inbox wiring (ADR 0005 / ADR 0009).</strong> Before the {@link
   * DeadLetterPublishingRecoverer} publishes to the DLT, the injected {@link ConsumeErrorRecorder}
   * writes a redacted, fingerprinted row to {@code error_log} in a {@code REQUIRES_NEW} transaction
   * that is independent of — and survives — any rolled-back business transaction. PII is redacted
   * at write time inside the lib; no raw exception text is stored or logged here.
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
      ConsumeErrorRecorder consumeErrorRecorder) {
    // Pin the DLT destination to "<topic>.DLT" explicitly (the convention this codebase and
    // ENGINEERING-STANDARDS §4 name); the broker chooses the partition (-1).
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            deadLetterKafkaTemplate,
            (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
    // Record to the error-inbox BEFORE publishing to the DLT so the row exists even if the DLT
    // publish itself fails. The lambda captures recoverer by reference — no field needed.
    ConsumerRecordRecoverer wrapped =
        (rec, ex) -> {
          consumeErrorRecorder.record(rec, ex);
          recoverer.accept(rec, ex);
        };
    DefaultErrorHandler handler =
        new DefaultErrorHandler(wrapped, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    handler.addNotRetryableExceptions(
        UncheckedIOException.class,
        OrgUnitDecodeException.class,
        MissingEventIdException.class,
        PayrollEventDecodeException.class,
        MissingPayrollEventIdException.class);
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
