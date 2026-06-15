package id.co.nativeapp.finance.group;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's CONSUMER copy of the {@code GroupDefined} Avro schema from the classpath
 * ({@code avro/GroupDefined.avsc}) and decodes raw Avro bytes into a {@link GroupDefinedEvent} — no
 * Avro code-generation plugin, and no Confluent / Schema Registry serde. finance owns its own
 * consumer view; {@code GroupDefinedContractTest} asserts this copy stays backward-compatible with
 * the producer's schema (rule 7).
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema — exactly how the {@code SaleRecorded}
 * / {@code ExpenseRecorded} consumer paths work.
 */
public final class GroupDefinedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/GroupDefined.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "GroupDefined";

  private static final Schema SCHEMA = parse();

  private GroupDefinedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code GroupDefined} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into a {@link
   * GroupDefinedEvent}, using this consumer copy of the schema.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static GroupDefinedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    return new GroupDefinedEvent(
        eventId,
        UUID.fromString(record.get("group_id").toString()),
        UUID.fromString(record.get("lead_company_id").toString()),
        record.get("reporting_currency").toString(),
        record.get("name").toString());
  }

  private static Schema parse() {
    try (InputStream in = GroupDefinedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
