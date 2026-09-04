package id.co.nativeapp.finance.inventory.messaging;

import id.co.nativeapp.finance.inventory.service.SaleCogsRecordedService;
import id.co.nativeapp.finance.revenue.messaging.MissingEventIdException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code SaleCogsRecorded} off Kafka and posts perpetual COGS on a sale's recipe depletion
 * (ADR 0067 Phase C). The exact {@code StockReceivedListener} contract: raw outbox Avro bytes; the
 * durable event id comes from the Debezium-stamped {@code id} header (missing/non-UUID → fail
 * closed → DLT — a synthesized id would defeat dedupe and risk double-posting); the tenant binds
 * from the EVENT's company inside {@link SaleCogsRecordedService}; a decode failure OR a violated
 * validity guard is poison → {@link SaleCogsRecordedDecodeException} → {@code
 * SaleCogsRecorded.DLT}.
 */
@Component
public class SaleCogsRecordedListener {

  private static final Logger log = LoggerFactory.getLogger(SaleCogsRecordedListener.class);

  private final SaleCogsRecordedService service;

  public SaleCogsRecordedListener(SaleCogsRecordedService service) {
    this.service = service;
  }

  /** Handles one record from the {@code SaleCogsRecorded} topic. */
  @KafkaListener(
      topics = SaleCogsRecordedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onSaleCogsRecorded(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    SaleCogsRecordedEvent event = decodeAndAssert(eventId, record);
    boolean posted = service.handle(event);
    if (!posted) {
      log.debug("Skipped re-delivered SaleCogsRecorded eventId={} (already processed)", eventId);
    }
  }

  /**
   * Decodes the payload AND re-asserts the validity guard — an event that cannot post correct money
   * is poison at decode time (same DLT path as garbage bytes).
   */
  private static SaleCogsRecordedEvent decodeAndAssert(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      SaleCogsRecordedEvent event = SaleCogsRecordedSchema.decode(eventId, record.value());
      event.assertValid();
      return event;
    } catch (RuntimeException failure) {
      throw new SaleCogsRecordedDecodeException(
          "Poison SaleCogsRecorded at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " (eventId="
              + eventId
              + "); routing to DLT",
          failure);
    }
  }

  /** The durable event id from the {@code id} header — fail closed when absent (see class doc). */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader("id");
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "SaleCogsRecorded record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no 'id' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new MissingEventIdException(
          "SaleCogsRecorded 'id' header is not a UUID (" + raw + "); routing to DLT", badId);
    }
  }
}
