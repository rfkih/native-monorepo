package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.restaurant.entitlement.messaging.EntitlementDecodeException;
import id.co.nativeapp.restaurant.entitlement.messaging.EntitlementMissingEventIdException;
import id.co.nativeapp.restaurant.loyaltyref.messaging.LoyaltyRefDecodeException;
import id.co.nativeapp.restaurant.loyaltyref.messaging.LoyaltyRefMissingEventIdException;
import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentDecodeException;
import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentMissingEventIdException;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeSucceededDecodeException;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeSucceededMissingEventIdException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer wiring for the {@code UserOutletAssignmentChanged} listener (Phase 5).
 *
 * <p><strong>Raw Avro bytes, no Schema Registry.</strong> The topic value is the org-service outbox
 * payload (raw Avro bytes shipped by Debezium, base64-transported and decoded by the container's
 * {@link id.co.nativeapp.events.Base64ByteArrayDeserializer}), so the value deserializer is
 * configured in {@code application.yml}; the listener decodes the bytes itself with {@code
 * libs/events AvroSerde} against {@code libs/contracts}'s shared schema. No Confluent kafka-avro-
 * serializer / Schema Registry serde — consistent with how the outbox stores events.
 *
 * <p><strong>Resilience (ENGINEERING-STANDARDS §4): bounded retries then DLT, never an infinite
 * in-place retry that blocks the partition.</strong> A non-transient failure is retried a bounded
 * number of times with a fixed backoff and then routed to {@code <topic>.DLT} via a {@link
 * DeadLetterPublishingRecoverer}. Idempotency is by event UUID ({@code ProcessedEventStore}), so
 * at-least-once redelivery never double-applies.
 *
 * <p><strong>Non-retryable exceptions.</strong> Deterministically poison payloads are classified as
 * non-retryable so the retry budget is not wasted:
 *
 * <ul>
 *   <li>{@link UserOutletAssignmentDecodeException} — the value is not a valid Avro payload;
 *   <li>{@link UserOutletAssignmentMissingEventIdException} — the record has no valid {@code id}
 *       header (a producer-side contract violation);
 *   <li>{@link LoyaltyRefDecodeException} — a {@code LoyaltyBalanceChanged}/{@code
 *       GiftCardStateChanged} payload is not valid Avro (ADR 0027, Phase 4);
 *   <li>{@link LoyaltyRefMissingEventIdException} — same as above, missing/invalid {@code id}
 *       header;
 *   <li>{@link PaymentChargeSucceededDecodeException} — a {@code PaymentChargeSucceeded} payload is
 *       not valid Avro (ADR 0045);
 *   <li>{@link PaymentChargeSucceededMissingEventIdException} — same as above, missing/invalid
 *       {@code id} header. Business-level anomalies on this event (unknown payment, state/amount
 *       mismatch, a failed capture) are parked in the error inbox instead of thrown, so they never
 *       reach this classifier.
 * </ul>
 */
@Configuration
public class KafkaConfig {

  /** Bounded retry budget before routing a record to the DLT. */
  private static final long RETRY_INTERVAL_MS = 500L;

  private static final long MAX_RETRIES = 3L;

  /** DLT topic suffix convention used fleet-wide. */
  static final String DLT_SUFFIX = ".DLT";

  /**
   * Typed listener container factory the {@code @KafkaListener} binds to: keys are {@link String},
   * values are {@code byte[]} (raw Avro). Built from the Boot-bound {@link KafkaProperties} so the
   * {@code application.yml} consumer config applies, with the DLT error handler attached.
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
    // ADR 0010 #13 — outbox→Kafka trace continuity: enable observation on this custom factory.
    factory.getContainerProperties().setObservationEnabled(true);
    return factory;
  }

  /**
   * Bounded-retry error handler that routes poison records to {@code <topic>.DLT} after the retry
   * budget is exhausted. The DLT destination is pinned to {@code <topic>.DLT} (the fleet
   * convention, deterministic across versions).
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, byte[]> deadLetterKafkaTemplate) {
    DeadLetterPublishingRecoverer dlpr =
        new DeadLetterPublishingRecoverer(
            deadLetterKafkaTemplate,
            (record, exception) ->
                new org.apache.kafka.common.TopicPartition(record.topic() + DLT_SUFFIX, -1));

    DefaultErrorHandler handler =
        new DefaultErrorHandler(dlpr, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    handler.addNotRetryableExceptions(
        java.io.UncheckedIOException.class,
        UserOutletAssignmentDecodeException.class,
        UserOutletAssignmentMissingEventIdException.class,
        LoyaltyRefDecodeException.class,
        LoyaltyRefMissingEventIdException.class,
        EntitlementDecodeException.class,
        EntitlementMissingEventIdException.class,
        PaymentChargeSucceededDecodeException.class,
        PaymentChargeSucceededMissingEventIdException.class);
    return handler;
  }

  /**
   * The {@link KafkaTemplate} the DLT recoverer publishes with. Uses a {@link
   * Base64ByteArraySerializer} for the value (the raw Avro bytes after {@link
   * id.co.nativeapp.events.Base64ByteArrayDeserializer} decoded them) so the poison record lands on
   * {@code <topic>.DLT} in the same wire format as the source topic.
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
