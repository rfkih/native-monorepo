package id.co.nativeapp.restaurant.payment.messaging;

import id.co.nativeapp.restaurant.payment.service.PaymentChargeExpiredService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PaymentChargeExpired} off Kafka (produced by payment-service when a dynamic-QRIS
 * gateway charge terminates without settling, ADR 0045) and routes it to {@link
 * PaymentChargeExpiredService}, which filters on {@code vertical} and RELEASES the PENDING tender
 * the charge was holding: a bill payment's {@code bill_line} reservation is released and the
 * payment abandoned; an order is reverted out of {@code AWAITING_PAYMENT} and its payment
 * abandoned. No money moves — the counterpart of {@link PaymentChargeSucceededListener} on the
 * un-happy path.
 *
 * <p><strong>Raw Avro bytes / idempotency by event id / DLT on decode-or-missing-header.</strong>
 * Identical mechanics to {@link PaymentChargeSucceededListener}: the value is the payment-service
 * outbox payload (raw Avro bytes shipped by Debezium) decoded via {@link
 * PaymentChargeExpiredConsumerSchema}; the dedupe key is the event UUID from the {@code id} header;
 * a decode error or a missing/invalid {@code id} header fails closed to {@code <topic>.DLT}; every
 * business-level anomaly parks in the error inbox in the writer and never throws here.
 */
@Component
public class PaymentChargeExpiredListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(PaymentChargeExpiredListener.class);

  private final PaymentChargeExpiredService service;

  public PaymentChargeExpiredListener(PaymentChargeExpiredService service) {
    this.service = service;
  }

  @KafkaListener(
      topics = PaymentChargeExpiredConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onPaymentChargeExpired(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    PaymentChargeExpiredEvent event = decodePayload(eventId, record);
    boolean applied = service.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered PaymentChargeExpired eventId={} paymentId={} (already processed)",
          eventId,
          event.paymentId());
    }
  }

  private static PaymentChargeExpiredEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return PaymentChargeExpiredConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new PaymentChargeExpiredDecodeException(
          "Failed to decode PaymentChargeExpired payload at "
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
   * PaymentChargeExpiredMissingEventIdException}, which the container's error handler treats as
   * non-retryable and routes straight to {@code <topic>.DLT}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new PaymentChargeExpiredMissingEventIdException(
          "PaymentChargeExpired record at "
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
      throw new PaymentChargeExpiredMissingEventIdException(
          "PaymentChargeExpired '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
