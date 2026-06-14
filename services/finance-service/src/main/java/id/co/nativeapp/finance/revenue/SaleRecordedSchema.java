package id.co.nativeapp.finance.revenue;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's CONSUMER copy of the {@code SaleRecorded} Avro schema from the classpath
 * ({@code avro/SaleRecorded.avsc}) and decodes raw Avro bytes into a {@link SaleRecordedEvent} — no
 * Avro code-generation plugin, and no Confluent / Schema Registry serde. finance owns its own
 * consumer view of the contract (full name {@code id.co.nativeapp.events.restaurant.SaleRecorded},
 * matching docs/EVENT-CATALOG.md); a contract test asserts this copy stays backward-compatible with
 * the producer's schema.
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema — consistent with how the outbox stores
 * events.
 */
public final class SaleRecordedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/SaleRecorded.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "SaleRecorded";

  private static final Schema SCHEMA = parse();

  private SaleRecordedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code SaleRecorded} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into a {@link
   * SaleRecordedEvent}, using this consumer copy of the schema as both writer and reader schema.
   *
   * <p>The money is reconstructed as {@code libs/money} {@link Money} from the integer {@code
   * amount_minor} + ISO-4217 {@code currency} (never a float); {@code occurred_at} is epoch millis
   * UTC.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static SaleRecordedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    return new SaleRecordedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        amount,
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse() {
    try (InputStream in = SaleRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
