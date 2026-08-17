package id.co.nativeapp.finance.inventory.messaging;

import id.co.nativeapp.finance.inventory.service.StockReceivedService;
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
 * Consumes {@code StockReceived} off Kafka and capitalizes a priced goods receipt (ADR 0067 Phase
 * B). The exact {@code StocktakeCompletedListener} contract: raw outbox Avro bytes; the durable
 * event id comes from the Debezium-stamped {@code id} header (missing/non-UUID → fail closed → DLT
 * — a synthesized id would defeat dedupe and risk double-posting); the tenant binds from the
 * EVENT's company inside {@link StockReceivedService}; a decode failure OR a violated validity
 * guard is poison → {@link StockReceivedDecodeException} → {@code StockReceived.DLT}.
 */
@Component
public class StockReceivedListener {

  private static final Logger log = LoggerFactory.getLogger(StockReceivedListener.class);

  private final StockReceivedService service;

  public StockReceivedListener(StockReceivedService service) {
    this.service = service;
  }

  /** Handles one record from the {@code StockReceived} topic. */
  @KafkaListener(
      topics = StockReceivedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onStockReceived(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    StockReceivedEvent event = decodeAndAssert(eventId, record);
    boolean posted = service.handle(event);
    if (!posted) {
      log.debug("Skipped re-delivered StockReceived eventId={} (already processed)", eventId);
    }
  }

  /**
   * Decodes the payload AND re-asserts the validity guard — an event that cannot post correct money
   * is poison at decode time (same DLT path as garbage bytes).
   */
  private static StockReceivedEvent decodeAndAssert(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      StockReceivedEvent event = StockReceivedSchema.decode(eventId, record.value());
      event.assertValid();
      return event;
    } catch (RuntimeException failure) {
      throw new StockReceivedDecodeException(
          "Poison StockReceived at "
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
          "StockReceived record at "
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
          "StockReceived 'id' header is not a UUID (" + raw + "); routing to DLT", badId);
    }
  }
}
