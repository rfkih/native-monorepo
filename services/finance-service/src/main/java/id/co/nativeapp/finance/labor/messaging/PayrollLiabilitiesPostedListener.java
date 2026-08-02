package id.co.nativeapp.finance.labor.messaging;

import id.co.nativeapp.finance.labor.service.PayrollLiabilityService;
import id.co.nativeapp.finance.revenue.messaging.MissingEventIdException;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PayrollLiabilitiesPosted} off Kafka and books the run's liability recognition
 * entry (ADR 0032, Track P phase P4) — the third payroll consumer, alongside {@link
 * id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedListener} and {@link
 * PayrollPostedListener}.
 *
 * <p><strong>Raw Avro bytes.</strong> The value is the producer outbox payload (raw Avro),
 * delivered as a {@code byte[]} by the shared {@code kafkaListenerContainerFactory}; decoded via
 * {@link PayrollLiabilitiesPostedSchema} with {@code libs/events AvroSerde}. No Schema Registry
 * serde.
 *
 * <p><strong>Idempotency by event id.</strong> The dedupe key is the event UUID from the {@code id}
 * header ({@link SaleRecordedListener#EVENT_ID_HEADER}); the dedupe + post happens transactionally
 * in {@link PayrollLiabilityService} / {@code PayrollLiabilityWriter}. A missing/non-UUID header
 * fails closed ({@link MissingEventIdException}) to the DLT; an undecodable payload surfaces a
 * non-retryable {@link PayrollLiabilitiesPostedDecodeException} routed to {@code
 * PayrollLiabilitiesPosted.DLT}.
 *
 * <p><strong>Tenant from the event.</strong> The handler is bound to the event's {@code company_id}
 * inside the service so RLS applies (no JWT on the consumer path).
 */
@Component
public class PayrollLiabilitiesPostedListener {

  private static final Logger log = LoggerFactory.getLogger(PayrollLiabilitiesPostedListener.class);

  private final PayrollLiabilityService liabilityService;

  public PayrollLiabilitiesPostedListener(PayrollLiabilityService liabilityService) {
    this.liabilityService = liabilityService;
  }

  /**
   * Handles one record from the {@code PayrollLiabilitiesPosted} topic. Decodes the raw Avro bytes,
   * derives the event id from the header, and delegates to the idempotent liability service. A
   * non-decodable value surfaces as a non-retryable {@link PayrollLiabilitiesPostedDecodeException}
   * routed straight to the DLT.
   */
  @KafkaListener(
      topics = PayrollLiabilitiesPostedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onPayrollLiabilitiesPosted(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    PayrollLiabilitiesPostedEvent event = decode(eventId, record);
    boolean posted = liabilityService.handle(event);
    if (!posted) {
      log.debug(
          "Skipped re-delivered PayrollLiabilitiesPosted eventId={} (already processed)", eventId);
    }
  }

  private static PayrollLiabilitiesPostedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return PayrollLiabilitiesPostedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new PayrollLiabilitiesPostedDecodeException(
          "Failed to decode PayrollLiabilitiesPosted payload at "
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
   * Reads the durable event id from the {@code id} header. A missing or non-UUID header is a
   * contract violation: the consumer FAILS CLOSED with {@link MissingEventIdException}, routed
   * non-retryably to the DLT.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "PayrollLiabilitiesPosted record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new MissingEventIdException(
          "PayrollLiabilitiesPosted '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
