package id.co.nativeapp.loyalty.ingest.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ingest.dto.GiftCardSoldFact;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads loyalty-service's CONSUMER copy of the {@code GiftCardSold} Avro schema from the classpath
 * ({@code avro/GiftCardSold.avsc}, the single shared {@code libs/contracts} resource, ADR 0003) and
 * decodes raw Avro bytes into a {@link GiftCardSoldFact}.
 */
public final class GiftCardSoldConsumerSchema {

  public static final String RESOURCE = "avro/GiftCardSold.avsc";
  public static final String TOPIC = "GiftCardSold";

  private static final Schema SCHEMA = parse();

  private GiftCardSoldConsumerSchema() {
    // static holder
  }

  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw {@code GiftCardSold} Avro bytes into a {@link GiftCardSoldFact}.
   *
   * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload
   * @param payload the raw Avro bytes off the topic
   */
  public static GiftCardSoldFact decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    return new GiftCardSoldFact(
        eventId,
        record.get("gift_card_sale_id").toString(),
        UUID.fromString(record.get("gift_card_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        (long) record.get("amount_minor"),
        record.get("currency").toString(),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        GiftCardSoldConsumerSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
