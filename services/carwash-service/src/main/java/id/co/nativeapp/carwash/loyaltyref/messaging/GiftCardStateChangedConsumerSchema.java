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
 * Loads carwash-service's CONSUMER copy of the {@code GiftCardStateChanged} Avro schema from the
 * classpath ({@code avro/GiftCardStateChanged.avsc}, the single {@code libs/contracts} source of
 * truth, ADR 0003) and decodes raw Avro bytes into a {@link GiftCardStateChangedEvent}.
 */
public final class GiftCardStateChangedConsumerSchema {

  public static final String RESOURCE = "avro/GiftCardStateChanged.avsc";
  public static final String TOPIC = "GiftCardStateChanged";

  private GiftCardStateChangedConsumerSchema() {}

  private static final class Holder {
    private static final Schema SCHEMA = parse();

    private Holder() {}
  }

  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * Decodes raw {@code GiftCardStateChanged} Avro bytes.
   *
   * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload
   * @param payload the raw Avro bytes off the topic
   */
  public static GiftCardStateChangedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.SCHEMA);
    return new GiftCardStateChangedEvent(
        eventId,
        UUID.fromString(record.get("gift_card_id").toString()),
        record.get("company_id").toString(),
        record.get("state").toString(),
        (long) record.get("balance_minor"),
        record.get("currency").toString(),
        (long) record.get("balance_seq"),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        GiftCardStateChangedConsumerSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
