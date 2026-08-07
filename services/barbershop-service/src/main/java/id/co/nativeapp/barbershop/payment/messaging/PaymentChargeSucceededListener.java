package id.co.nativeapp.barbershop.payment.messaging;

import id.co.nativeapp.barbershop.config.EventDecodeException;
import id.co.nativeapp.barbershop.config.MissingEventIdException;
import id.co.nativeapp.barbershop.payment.service.PaymentChargeSucceededService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PaymentChargeSucceeded} off Kafka (produced by payment-service, ADR 0045) —
 * emitted when a dynamic-QRIS gateway charge (the merchant's OWN Midtrans account) settles. Every
 * vertical consumes the SAME topic and filters on {@code vertical}; this listener decodes every
 * record and delegates the barbershop-or-skip decision, the ticket lookup, the amount/currency
 * verification, and the idempotent capture to {@link PaymentChargeSucceededService} / {@code
 * payment.service.PaymentChargeSucceededWriter} — mirrors {@link
 * id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedListener} / {@link
 * id.co.nativeapp.barbershop.entitlement.messaging.EntitlementEventListener} EXACTLY (barbershop's
 * established consumer idiom, itself a faithful clone of carwash-service's).
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the payment-service outbox payload — raw
 * Avro bytes shipped by Debezium (base64 on the wire, decoded back by the container's {@code
 * Base64ByteArrayDeserializer}) — so the container delivers a {@code byte[]} value; this listener
 * decodes it with {@code libs/events AvroSerde} via {@link PaymentChargeSucceededConsumerSchema}.
 * No Schema Registry serde is used.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable event id (not the offset) survives rebalances and compacted replay (HR-3); the dedupe +
 * capture decision happen transactionally in {@code payment.service.PaymentChargeSucceededWriter}.
 * A record arriving WITHOUT a valid {@code id} header is a producer-side contract violation: the
 * listener fails closed (see {@link #eventIdOf}) so the record is DLT'd.
 *
 * <p>A non-transient (infrastructure) failure is retried a bounded number of times and then routed
 * to {@code <topic>.DLT} by the container's error handler — never an infinite in-place retry that
 * blocks the partition. A BUSINESS anomaly (wrong vertical, missing/unknown ticket, state or amount
 * mismatch, or a business exception from capture) never reaches the DLT path: it is parked in the
 * error inbox and the event is marked processed (park-don't-drop, ADR 0005/0009).
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
          "Skipped re-delivered PaymentChargeSucceeded eventId={} chargeId={} (already processed)",
          eventId,
          event.chargeId());
    }
  }

  private static PaymentChargeSucceededEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return PaymentChargeSucceededConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new EventDecodeException(
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
   * is a contract violation: we FAIL CLOSED — throwing {@link MissingEventIdException}, which the
   * container's error handler treats as non-retryable and routes straight to {@code <topic>.DLT}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
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
      throw new MissingEventIdException(
          "PaymentChargeSucceeded '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
