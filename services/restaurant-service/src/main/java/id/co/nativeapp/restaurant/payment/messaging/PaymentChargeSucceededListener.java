package id.co.nativeapp.restaurant.payment.messaging;

import id.co.nativeapp.restaurant.payment.service.PaymentChargeSucceededService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PaymentChargeSucceeded} off Kafka (produced by payment-service when a
 * dynamic-QRIS gateway charge settles, ADR 0045) and routes it to {@link
 * PaymentChargeSucceededService}, which filters on {@code vertical}, verifies the PENDING payment's
 * amount/currency, and runs the EXISTING idempotent {@code
 * PaymentCaptureWriter#capture(java.util.UUID)} — so {@code SaleRecorded} and finance's QRIS GL
 * routing need ZERO change (rule 2 — no synchronous call to payment-service).
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the payment-service outbox payload — raw
 * Avro bytes shipped by Debezium — so the container delivers a {@code byte[]} value; this listener
 * decodes it with {@code libs/events AvroSerde} via {@link PaymentChargeSucceededConsumerSchema}.
 * No Schema Registry serde is used.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable event id (not the offset) survives rebalances and compacted replay; the dedupe + business
 * logic happen transactionally in {@code payment.service.PaymentChargeSucceededWriter}. A record
 * arriving WITHOUT a valid {@code id} header is a producer-side contract violation: the listener
 * fails closed (see {@link #eventIdOf}) so the record is DLT'd.
 *
 * <p>A non-transient failure (decode error, missing header) is routed immediately to {@code
 * <topic>.DLT} by the container's error handler — never an infinite in-place retry that blocks the
 * partition (see {@link id.co.nativeapp.restaurant.config.KafkaConfig}). Business-level anomalies
 * (unknown payment, state mismatch, amount mismatch, a failed capture) never throw here — they are
 * parked in the error inbox and processed-marked by the writer, so they never reach the DLT.
 *
 * <p>Mirrors {@link id.co.nativeapp.restaurant.loyaltyref.messaging.GiftCardStateChangedListener}
 * in every particular.
 */
@Component
public class PaymentChargeSucceededListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(PaymentChargeSucceededListener.class);

  private final PaymentChargeSucceededService service;

  public PaymentChargeSucceededListener(PaymentChargeSucceededService service) {
    this.service = service;
  }

  @KafkaListener(
      topics = PaymentChargeSucceededConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onPaymentChargeSucceeded(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    PaymentChargeSucceededEvent event = decodePayload(eventId, record);
    boolean applied = service.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered PaymentChargeSucceeded eventId={} paymentId={} (already"
              + " processed)",
          eventId,
          event.paymentId());
    }
  }

  private static PaymentChargeSucceededEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return PaymentChargeSucceededConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new PaymentChargeSucceededDecodeException(
          "Failed to decode PaymentChargeSucceeded payload at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " (eventId="
              + eventId
              + "); routing to DLT",
          decodeFailure);
    }
  }

  /**
   * Reads the durable event id from the {@code id} header. A missing or non-UUID {@code id} header
   * is a contract violation: we FAIL CLOSED — throwing {@link
   * PaymentChargeSucceededMissingEventIdException}, which the container's error handler treats as
   * non-retryable and routes straight to {@code <topic>.DLT}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new PaymentChargeSucceededMissingEventIdException(
          "PaymentChargeSucceeded record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no '"
              + EVENT_ID_HEADER
              + "' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new PaymentChargeSucceededMissingEventIdException(
          "PaymentChargeSucceeded '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
