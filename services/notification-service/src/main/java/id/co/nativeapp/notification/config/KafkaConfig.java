package id.co.nativeapp.notification.config;

import id.co.nativeapp.notification.notification.messaging.ConsolidationClosedDecodeException;
import id.co.nativeapp.notification.notification.messaging.MissingEventIdException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
 * Kafka consumer wiring for the {@code ConsolidationClosed} trigger listener — the same shape
 * finance-service / carwash-service use.
 *
 * <p><strong>Raw Avro bytes, no Schema Registry.</strong> The topic value is the finance outbox
 * payload (raw Avro bytes shipped by Debezium), so the value deserializer is a plain {@code
 * ByteArrayDeserializer} (configured in {@code application.yml}); the listener decodes the bytes
 * itself with {@code libs/events AvroSerde} against this service's own consumer copy of the schema.
 * We deliberately do NOT use a Confluent kafka-avro-serializer / Schema Registry serde.
 *
 * <p><strong>Resilience (ENGINEERING-STANDARDS §4): bounded retries then DLT, never an infinite
 * in-place retry that blocks the partition.</strong> A non-transient failure is retried a bounded
 * number of times with a fixed backoff and then routed to {@code <topic>.DLT} via a {@link
 * DeadLetterPublishingRecoverer}, so a poison record cannot stall the consumer. The two
 * deterministic poison failures ({@link ConsolidationClosedDecodeException}, {@link
 * MissingEventIdException}) are classified non-retryable so they are DLT'd immediately rather than
 * wasting the retry budget. Idempotency is by event UUID (the {@code ProcessedEventStore}), so the
 * at-least-once redelivery these retries cause never creates a duplicate notification.
 *
 * <p><strong>A delivery FAILURE is NOT a poison record.</strong> A stub-simulated send failure is a
 * normal, recorded outcome (a FAILED notification + a FAILED delivery_receipt + a FAILED
 * DeliveryReceipt event), committed in the same transaction — it does NOT throw out of the handler
 * and so never reaches the error handler / DLT. Only an undecodable payload or a missing {@code id}
 * header routes here.
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
   * The typed listener container factory the {@code @KafkaListener} binds to: keys are {@link
   * String}, values are {@code byte[]} (raw Avro). Built from the Boot-bound {@link
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
    return factory;
  }

  /**
   * Bounded-retry error handler that routes a poison record to {@code <topic>.DLT} after the retry
   * budget is exhausted. The deterministic poison failures are classified non-retryable so they are
   * DLT'd immediately.
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, byte[]> deadLetterKafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            deadLetterKafkaTemplate,
            (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
    DefaultErrorHandler handler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    handler.addNotRetryableExceptions(
        UncheckedIOException.class,
        ConsolidationClosedDecodeException.class,
        MissingEventIdException.class);
    return handler;
  }

  /**
   * The {@link KafkaTemplate} the DLT recoverer publishes with. The original record is a {@code
   * (String key, byte[] value)} — the raw Avro payload — so the producer MUST use a {@link
   * StringSerializer} key and a {@link ByteArraySerializer} value, overriding the auto-configured
   * template's String/String defaults (which would throw {@code ClassCastException: [B cannot be
   * cast to String} on every DLT publish and leave the poison record looping on the partition).
   */
  @Bean
  public KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(KafkaProperties kafkaProperties) {
    Map<String, Object> producerProps = new HashMap<>(kafkaProperties.buildProducerProperties());
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    ProducerFactory<String, byte[]> producerFactory =
        new DefaultKafkaProducerFactory<>(producerProps);
    return new KafkaTemplate<>(producerFactory);
  }
}
