package id.co.nativeapp.restaurant.inventory.messaging;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Consumer-side holder for the {@code InventoryPurchaseRecorded} schema (ADR 0072) — the SAME
 * {@code libs/contracts} {@code .avsc} the finance producer writes with, so a produced event is
 * decode-compatible by construction. Decodes the raw outbox bytes (shipped by Debezium) into an
 * {@link InventoryPurchaseRecordedEvent}. {@code InventoryPurchaseRecordedContractTest} pins the
 * rule-7 triad.
 */
public final class InventoryPurchaseRecordedConsumerSchema {

  /** Classpath location of the {@code .avsc} (the shared libs/contracts copy). */
  public static final String RESOURCE = "avro/InventoryPurchaseRecorded.avsc";

  /** The Kafka topic this service consumes (one topic per event type). */
  public static final String TOPIC = "InventoryPurchaseRecorded";

  private static final Schema SCHEMA = parse();

  private InventoryPurchaseRecordedConsumerSchema() {
    // static holder
  }

  /** The parsed schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the finance outbox payload) into the consumer event.
   *
   * @param eventId the durable event UUID from the {@code id} header — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static InventoryPurchaseRecordedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    List<InventoryPurchaseRecordedEvent.Line> lines = new ArrayList<>();
    Object raw = record.get("lines");
    if (!(raw instanceof Iterable<?> iterable)) {
      throw new IllegalArgumentException("InventoryPurchaseRecorded 'lines' is not an array");
    }
    for (Object element : iterable) {
      GenericRecord line = (GenericRecord) element;
      lines.add(
          new InventoryPurchaseRecordedEvent.Line(
              UUID.fromString(line.get("line_id").toString()),
              UUID.fromString(line.get("ingredient_id").toString()),
              (Long) line.get("qty_base"),
              (Long) line.get("value_minor")));
    }
    return new InventoryPurchaseRecordedEvent(
        eventId,
        UUID.fromString(record.get("purchase_id").toString()),
        record.get("source").toString(),
        record.get("company_id").toString(),
        record.get("currency").toString(),
        java.time.Instant.ofEpochMilli((Long) record.get("occurred_at")),
        List.copyOf(lines));
  }

  private static Schema parse() {
    try (InputStream in =
        InventoryPurchaseRecordedConsumerSchema.class
            .getClassLoader()
            .getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
