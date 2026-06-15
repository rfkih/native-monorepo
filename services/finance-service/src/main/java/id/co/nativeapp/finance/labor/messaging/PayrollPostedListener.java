package id.co.nativeapp.finance.labor.messaging;

import id.co.nativeapp.finance.labor.service.PayrollReconciliationService;
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
 * Consumes {@code PayrollPosted} off Kafka and reconciles the run-level control event against the
 * sum of its {@code LaborCostAllocated} buckets (#23). Produces NO ledger posting — mirrors the
 * idempotency + DLT discipline of the {@code ExpenseRecorded} path.
 *
 * <p><strong>Raw Avro bytes.</strong> The value is the producer outbox payload (raw Avro),
 * delivered as a {@code byte[]} by the shared {@code kafkaListenerContainerFactory}; decoded via
 * {@link PayrollPostedSchema} with {@code libs/events AvroSerde}. No Schema Registry serde.
 *
 * <p><strong>Idempotency by event id.</strong> The dedupe key is the event UUID from the {@code id}
 * header ({@link SaleRecordedListener#EVENT_ID_HEADER}); the dedupe + reconcile happens
 * transactionally in {@link PayrollReconciliationService}. A missing/non-UUID header fails closed
 * ({@link MissingEventIdException}) to the DLT; an undecodable payload surfaces a non-retryable
 * {@link PayrollPostedDecodeException} routed to {@code PayrollPosted.DLT}.
 *
 * <p><strong>Tenant from the event.</strong> The handler is bound to the event's {@code company_id}
 * inside the service so RLS applies (no JWT on the consumer path).
 */
@Component
public class PayrollPostedListener {

  private static final Logger log = LoggerFactory.getLogger(PayrollPostedListener.class);

  private final PayrollReconciliationService reconciliationService;

  public PayrollPostedListener(PayrollReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  /**
   * Handles one record from the {@code PayrollPosted} topic. Decodes the raw Avro bytes, derives
   * the event id from the header, and delegates to the idempotent reconciliation service. A
   * non-decodable value surfaces as a non-retryable {@link PayrollPostedDecodeException} routed
   * straight to the DLT.
   */
  @KafkaListener(
      topics = PayrollPostedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onPayrollPosted(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    PayrollPostedEvent event = decode(eventId, record);
    boolean reconciled = reconciliationService.handle(event);
    if (!reconciled) {
      log.debug("Skipped re-delivered PayrollPosted eventId={} (already processed)", eventId);
    }
  }

  private static PayrollPostedEvent decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return PayrollPostedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new PayrollPostedDecodeException(
          "Failed to decode PayrollPosted payload at "
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
          "PayrollPosted record at "
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
          "PayrollPosted '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
