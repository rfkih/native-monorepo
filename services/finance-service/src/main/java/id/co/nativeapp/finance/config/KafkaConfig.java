package id.co.nativeapp.finance.config;

import id.co.nativeapp.events.Base64ByteArraySerializer;
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
 * Kafka consumer wiring for the {@code SaleRecorded} listener.
 *
 * <p><strong>Raw Avro bytes, no Schema Registry.</strong> The topic value is the producer outbox
 * payload (raw Avro bytes shipped by Debezium), so the value deserializer is a plain {@link
 * org.apache.kafka.common.serialization.ByteArrayDeserializer} (configured in {@code
 * application.yml}); the listener decodes the bytes itself with {@code libs/events AvroSerde}
 * against finance's own consumer copy of the schema. We deliberately do NOT use a Confluent
 * kafka-avro-serializer / Schema Registry serde — consistent with how the outbox stores events.
 *
 * <p><strong>Resilience (HR / ENGINEERING-STANDARDS §4): bounded retries then DLT, never an
 * infinite in-place retry that blocks the partition.</strong> A non-transient failure is retried a
 * bounded number of times with a fixed backoff and then routed to {@code <topic>.DLT} via a {@link
 * DeadLetterPublishingRecoverer}, so a poison record cannot stall finance consolidation.
 * Idempotency is by event UUID (the {@code ProcessedEventStore}), so the at-least-once redelivery
 * these retries cause never double-counts.
 */
@Configuration
public class KafkaConfig {

  /**
   * Bounded retry budget before a record is sent to the DLT (never an unbounded in-place retry).
   */
  private static final long RETRY_INTERVAL_MS = 500L;

  private static final long MAX_RETRIES = 3L;

  /**
   * The dead-letter topic suffix — a poison {@code SaleRecorded} goes to {@code SaleRecorded.DLT}.
   */
  static final String DLT_SUFFIX = ".DLT";

  /**
   * The typed listener container factory the {@code SaleRecorded} {@code @KafkaListener} binds to:
   * keys are {@link String}, values are {@code byte[]} (raw Avro). Built from the Boot-bound {@link
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
    return factory;
  }

  /**
   * Bounded-retry error handler that routes a poison record to {@code <topic>.DLT} after the retry
   * budget is exhausted. A {@link ConsumerRecord} whose value failed to deserialize (an {@link
   * ErrorHandlingDeserializer}-wrapped failure) is not retried — it goes straight to the DLT.
   *
   * <p>Two finance-specific failures are also classified as <strong>non-retryable</strong>, so a
   * deterministic poison record is DLT'd immediately instead of wasting the retry budget on a
   * record that can never succeed (and never silently dropping money):
   *
   * <ul>
   *   <li>{@code SaleRecordedDecodeException} — the value is not a valid {@code SaleRecorded} Avro
   *       payload (garbage / truncated bytes); and
   *   <li>{@code MissingEventIdException} — the record has no valid durable {@code id} header, a
   *       producer-side contract violation the consumer fails closed on.
   * </ul>
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, byte[]> deadLetterKafkaTemplate) {
    // Pin the DLT destination to "<topic>.DLT" explicitly (the convention this codebase and
    // ENGINEERING-STANDARDS §4 name). spring-kafka's default suffix has varied across versions
    // (e.g. "-dlt"), so resolving it ourselves keeps the topic name deterministic and lets the
    // partition be chosen by the broker (partition = -1) regardless of the source partition count.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            deadLetterKafkaTemplate,
            (record, exception) ->
                new org.apache.kafka.common.TopicPartition(record.topic() + DLT_SUFFIX, -1));
    DefaultErrorHandler handler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    handler.addNotRetryableExceptions(
        java.io.UncheckedIOException.class,
        id.co.nativeapp.finance.revenue.messaging.SaleRecordedDecodeException.class,
        id.co.nativeapp.finance.expense.messaging.ExpenseRecordedDecodeException.class,
        id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedDecodeException.class,
        id.co.nativeapp.finance.labor.messaging.PayrollPostedDecodeException.class,
        id.co.nativeapp.finance.group.messaging.GroupDefinedDecodeException.class,
        id.co.nativeapp.finance.group.messaging.GroupMembershipChangedDecodeException.class,
        id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedDecodeException.class,
        id.co.nativeapp.finance.revenue.messaging.MissingEventIdException.class);
    // NOTE: GroupMembershipChanged's / TrialBalancePublished's UnknownGroupException is
    // deliberately
    // NOT listed — it is a TRANSIENT reorder (GroupDefined not yet consumed), so it MUST be
    // retried,
    // not DLT'd on the first attempt; only after the bounded retry budget is exhausted does it
    // reach
    // the DLT.
    return handler;
  }

  /**
   * The {@link KafkaTemplate} the DLT recoverer publishes with. The original record's value, after
   * the consumer's {@link id.co.nativeapp.events.Base64ByteArrayDeserializer} ran, is the RAW Avro
   * {@code byte[]} — so re-publishing it to the DLT must use the SAME base64 transport encoding as
   * the source topic, i.e. a {@link StringSerializer} key and a {@link Base64ByteArraySerializer}
   * value. The auto-configured template defaults both serializers to {@code StringSerializer} (no
   * producer serializers are set in {@code application.yml} since finance only consumes), which
   * would throw {@code ClassCastException: [B cannot be cast to String} on every DLT publish and
   * leave the poison record looping on the partition. This dedicated template sets the base64 value
   * serializer so a poison {@code SaleRecorded} lands on {@code SaleRecorded.DLT} in the same wire
   * format as the source topic.
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
