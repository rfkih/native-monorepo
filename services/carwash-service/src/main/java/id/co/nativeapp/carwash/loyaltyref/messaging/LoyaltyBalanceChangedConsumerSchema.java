package id.co.nativeapp.carwash.loyaltyref.messaging;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads carwash-service's CONSUMER copy of the {@code LoyaltyBalanceChanged} Avro schema from the
 * classpath ({@code avro/LoyaltyBalanceChanged.avsc}, the single {@code libs/contracts} source of
 * truth, ADR 0003) and decodes raw Avro bytes into a {@link LoyaltyBalanceChangedEvent}.
 *
 * <p>No Avro code-generation plugin, no Schema Registry serde — consistent with every other
 * consumer in this fleet (mirrors {@code outletref.messaging.UserOutletAssignmentChangedSchemas}).
 */
public final class LoyaltyBalanceChangedConsumerSchema {

  public static final String RESOURCE = "avro/LoyaltyBalanceChanged.avsc";
  public static final String TOPIC = "LoyaltyBalanceChanged";

  private LoyaltyBalanceChangedConsumerSchema() {}

  private static final class Holder {
    private static final Schema SCHEMA = parse();

    private Holder() {}
  }

  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * Decodes raw {@code LoyaltyBalanceChanged} Avro bytes.
   *
   * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload
   * @param payload the raw Avro bytes off the topic
   */
  public static LoyaltyBalanceChangedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.SCHEMA);
    return new LoyaltyBalanceChangedEvent(
        eventId,
        UUID.fromString(record.get("member_id").toString()),
        record.get("company_id").toString(),
        (long) record.get("points_balance"),
        (long) record.get("balance_seq"),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        LoyaltyBalanceChangedConsumerSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
