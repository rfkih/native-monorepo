package id.co.nativeapp.restaurant.inventory.messaging;

import id.co.nativeapp.restaurant.inventory.service.InventoryPurchaseApplyService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code InventoryPurchaseRecorded} off Kafka (produced by finance-service when a company
 * expense or posted AP bill carries ingredient lines, ADR 0072) and routes it to {@link
 * InventoryPurchaseApplyService}, which applies each line as a priced goods receipt — moving-
 * average value-add + {@code goods_receipt} anchor + {@code StockReceived} — keyed per line on
 * {@code goods_receipt.idempotency_key = line_id}.
 *
 * <p><strong>Raw Avro bytes / idempotency by event id / DLT on decode-or-missing-header.</strong>
 * Identical mechanics to {@link
 * id.co.nativeapp.restaurant.payment.messaging.PaymentChargeExpiredListener}: the value is the
 * finance outbox payload decoded via {@link InventoryPurchaseRecordedConsumerSchema}; the dedupe
 * key is the event UUID from the {@code id} header; a decode error or missing/invalid header fails
 * closed to {@code <topic>.DLT}; every business-level anomaly (unknown ingredient, currency
 * mismatch, qty overflow, key conflict) parks in the error inbox in the writer and never throws
 * here.
 */
@Component
public class InventoryPurchaseRecordedListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log =
      LoggerFactory.getLogger(InventoryPurchaseRecordedListener.class);

  private final InventoryPurchaseApplyService service;

  public InventoryPurchaseRecordedListener(InventoryPurchaseApplyService service) {
    this.service = service;
  }

  @KafkaListener(
      topics = InventoryPurchaseRecordedConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onInventoryPurchaseRecorded(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    InventoryPurchaseRecordedEvent event = decodePayload(eventId, record);
    boolean applied = service.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered InventoryPurchaseRecorded eventId={} purchaseId={} (already"
              + " processed)",
          eventId,
          event.purchaseId());
    }
  }

  private static InventoryPurchaseRecordedEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return InventoryPurchaseRecordedConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new InventoryPurchaseRecordedDecodeException(
          "Failed to decode InventoryPurchaseRecorded payload at "
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
   * is a contract violation: FAIL CLOSED to {@code <topic>.DLT}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new InventoryPurchaseRecordedMissingEventIdException(
          "InventoryPurchaseRecorded record at "
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
      throw new InventoryPurchaseRecordedMissingEventIdException(
          "InventoryPurchaseRecorded '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
