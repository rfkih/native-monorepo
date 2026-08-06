package id.co.nativeapp.finance.stocktake.messaging;

import id.co.nativeapp.finance.revenue.messaging.MissingEventIdException;
import id.co.nativeapp.finance.stocktake.service.StocktakeService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code StocktakeCompleted} off Kafka and posts the net valued shrinkage (ADR 0038 phase
 * 3). The exact {@code RegisterSessionClosedListener} contract: raw outbox Avro bytes; the durable
 * event id comes from the Debezium-stamped {@code id} header (missing/non-UUID → fail closed → DLT
 * — a synthesized id would defeat dedupe and risk double-posting); the tenant binds from the
 * EVENT's company inside {@link StocktakeService}; a decode failure OR a violated validity guard is
 * poison → {@link StocktakeCompletedDecodeException} → {@code StocktakeCompleted.DLT}.
 */
@Component
public class StocktakeCompletedListener {

  private static final Logger log = LoggerFactory.getLogger(StocktakeCompletedListener.class);

  private final StocktakeService service;

  public StocktakeCompletedListener(StocktakeService service) {
    this.service = service;
  }

  /** Handles one record from the {@code StocktakeCompleted} topic. */
  @KafkaListener(
      topics = StocktakeCompletedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onStocktakeCompleted(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    StocktakeCompletedEvent event = decodeAndAssert(eventId, record);
    boolean posted = service.handle(event);
    if (!posted) {
      log.debug("Skipped re-delivered StocktakeCompleted eventId={} (already processed)", eventId);
    }
  }

  /**
   * Decodes the payload AND re-asserts the validity guard — an event that cannot post correct money
   * is poison at decode time (same DLT path as garbage bytes).
   */
  private static StocktakeCompletedEvent decodeAndAssert(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      StocktakeCompletedEvent event = StocktakeCompletedSchema.decode(eventId, record.value());
      event.assertValid();
      return event;
    } catch (RuntimeException failure) {
      throw new StocktakeCompletedDecodeException(
          "Poison StocktakeCompleted at "
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
          "StocktakeCompleted record at "
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
          "StocktakeCompleted 'id' header is not a UUID (" + raw + "); routing to DLT", badId);
    }
  }
}
