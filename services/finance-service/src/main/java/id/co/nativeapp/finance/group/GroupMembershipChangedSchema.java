package id.co.nativeapp.finance.group;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's CONSUMER copy of the {@code GroupMembershipChanged} Avro schema from the
 * classpath ({@code avro/GroupMembershipChanged.avsc}) and decodes raw Avro bytes into a {@link
 * GroupMembershipChangedEvent} — no Avro code-generation plugin, and no Schema Registry serde.
 * {@code GroupMembershipChangedContractTest} asserts this copy stays backward-compatible with the
 * producer's schema (rule 7).
 *
 * <p>{@code effective_from} / {@code effective_to} are Avro {@code date} logical-type ints (epoch
 * day). With the default {@link org.apache.avro.generic.GenericData}, no date conversion is
 * registered, so the decoder yields a plain {@code int}; this class converts it back to a {@link
 * LocalDate} via {@link LocalDate#ofEpochDay(long)}.
 */
public final class GroupMembershipChangedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/GroupMembershipChanged.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "GroupMembershipChanged";

  private static final Schema SCHEMA = parse();

  private GroupMembershipChangedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code GroupMembershipChanged} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes into a {@link GroupMembershipChangedEvent}, using this consumer copy of
   * the schema. The effective-date ints (epoch day) are converted to {@link LocalDate}.
   *
   * @param eventId the event's UUID — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static GroupMembershipChangedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    return new GroupMembershipChangedEvent(
        eventId,
        UUID.fromString(record.get("group_id").toString()),
        UUID.fromString(record.get("member_company_id").toString()),
        record.get("change_kind").toString(),
        LocalDate.ofEpochDay(((Number) record.get("effective_from")).longValue()),
        LocalDate.ofEpochDay(((Number) record.get("effective_to")).longValue()));
  }

  private static Schema parse() {
    try (InputStream in =
        GroupMembershipChangedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
