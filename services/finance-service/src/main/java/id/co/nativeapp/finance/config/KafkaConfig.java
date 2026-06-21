package id.co.nativeapp.finance.config;

import id.co.nativeapp.errorinbox.ConsumeErrorRecorder;
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
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
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
 *
 * <p><strong>Error inbox (ADR 0005).</strong> Before each DLT publish the {@link
 * id.co.nativeapp.errorinbox.ConsumeErrorRecorder} upserts the failure into the {@code error_log}
 * ops table (fingerprint dedup + occurrence count) and fires a webhook alert on first occurrence
 * and configured milestones. The recording runs in a {@code REQUIRES_NEW} transaction so it
 * survives the rolled-back business transaction.
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
   * <p>The deterministic poison failures below are also classified as
   * <strong>non-retryable</strong>, so a record that can NEVER succeed is DLT'd immediately instead
   * of wasting the retry budget (and never silently dropping money):
   *
   * <ul>
   *   <li>the per-event {@code *DecodeException} — the value is not a valid Avro payload for that
   *       event (garbage / truncated bytes);
   *   <li>{@code MissingEventIdException} — the record has no valid durable {@code id} header, a
   *       producer-side contract violation the consumer fails closed on; and
   *   <li>{@code MismatchedPostingCurrencyException} — a revenue/expense posting whose currency
   *       diverges from the company's immutable base currency for the period (#26): no FX in this
   *       path and a company has one base currency, so it can never post and is quarantined to DLT.
   * </ul>
   *
   * <p>The transient {@code UnknownGroupException} is deliberately NOT in this set (see the note in
   * the method body) — it is a reorder that MUST be retried, not DLT'd on the first attempt.
   *
   * <p><strong>Error inbox (ADR 0005):</strong> before publishing to the DLT the {@link
   * ConsumeErrorRecorder} upserts the failure into {@code error_log} (REQUIRES_NEW, so it survives
   * this rolled-back business tx) and fires a webhook alert on first occurrence and configured
   * milestones.
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
      ConsumeErrorRecorder consumeErrorRecorder) {
    // Pin the DLT destination to "<topic>.DLT" explicitly (the convention this codebase and
    // ENGINEERING-STANDARDS §4 name). spring-kafka's default suffix has varied across versions
    // (e.g. "-dlt"), so resolving it ourselves keeps the topic name deterministic and lets the
    // partition be chosen by the broker (partition = -1) regardless of the source partition count.
    DeadLetterPublishingRecoverer dlpr =
        new DeadLetterPublishingRecoverer(
            deadLetterKafkaTemplate,
            (record, exception) ->
                new org.apache.kafka.common.TopicPartition(record.topic() + DLT_SUFFIX, -1));

    // Wrap the recoverer: record the error in the inbox BEFORE publishing to the DLT so that a
    // DLT publish failure doesn't suppress the inbox entry.
    ConsumerRecordRecoverer wrapped =
        (rec, ex) -> {
          consumeErrorRecorder.record(rec, ex);
          dlpr.accept(rec, ex);
        };

    DefaultErrorHandler handler =
        new DefaultErrorHandler(wrapped, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    handler.addNotRetryableExceptions(
        java.io.UncheckedIOException.class,
        id.co.nativeapp.finance.revenue.messaging.SaleRecordedDecodeException.class,
        id.co.nativeapp.finance.expense.messaging.ExpenseRecordedDecodeException.class,
        id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedDecodeException.class,
        id.co.nativeapp.finance.labor.messaging.PayrollPostedDecodeException.class,
        id.co.nativeapp.finance.group.messaging.GroupDefinedDecodeException.class,
        id.co.nativeapp.finance.group.messaging.GroupMembershipChangedDecodeException.class,
        id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedDecodeException.class,
        id.co.nativeapp.finance.reversal.messaging.SaleVoidedDecodeException.class,
        id.co.nativeapp.finance.reversal.messaging.SaleRefundedDecodeException.class,
        id.co.nativeapp.finance.revenue.messaging.MissingEventIdException.class,
        // A posting whose currency diverges from the period's established base currency (#26) is a
        // deterministic producer contract violation (a company has one immutable base currency) —
        // it can NEVER succeed on retry, so DLT it immediately rather than burning the retry
        // budget.
        id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException.class,
        // A partial refund (refundAmount < originalGrandTotal) cannot be posted per-leg with exact
        // integer arithmetic; it is deterministically unsupported until SaleRefunded v2 carries
        // pre-computed prorated amounts. DLT it immediately — retrying cannot fix it.
        id.co.nativeapp.finance.reversal.service.PartialRefundNotSupportedException.class);
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
