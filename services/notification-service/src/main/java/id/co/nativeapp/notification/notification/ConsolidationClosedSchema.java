package id.co.nativeapp.notification.notification;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads notification-service's CONSUMER copy of the {@code ConsolidationClosed} Avro schema from
 * the classpath ({@code avro/ConsolidationClosed.avsc}) and decodes raw Avro bytes into a {@link
 * ConsolidationClosedEvent} — no Avro code-generation plugin, and no Confluent / Schema Registry
 * serde (the same approach finance-service / carwash-service use for their consumed events).
 * notification owns its own consumer view of the contract (full name {@code
 * id.co.nativeapp.events.finance.ConsolidationClosed}, matching docs/EVENT-CATALOG.md); a contract
 * test asserts this copy stays backward-compatible with the producer schema (rule 7).
 *
 * <p>The wire bytes are the finance outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema.
 */
public final class ConsolidationClosedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/ConsolidationClosed.avsc";

  /** The Kafka topic the finance outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "ConsolidationClosed";

  private static final Schema SCHEMA = parse();

  private ConsolidationClosedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code ConsolidationClosed} (notification's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the finance outbox payload, shipped by Debezium) into a {@link
   * ConsolidationClosedEvent}, using this consumer copy of the schema as both writer and reader
   * schema.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static ConsolidationClosedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Object groupId = record.get("group_id");
    return new ConsolidationClosedEvent(
        eventId,
        record.get("company_id").toString(),
        groupId == null ? null : groupId.toString(),
        record.get("period").toString());
  }

  private static Schema parse() {
    try (InputStream in =
        ConsolidationClosedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
