package id.co.nativeapp.loyalty.ingest.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ingest.dto.SaleRecordedFact;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads loyalty-service's CONSUMER copy of the {@code SaleRecorded} Avro schema from the classpath
 * ({@code avro/SaleRecorded.avsc}) and decodes raw Avro bytes into a {@link SaleRecordedFact}.
 *
 * <p><strong>Single source of truth (ADR 0003).</strong> Unlike a per-service consumer copy, this
 * resolves the SAME {@code avro/SaleRecorded.avsc} classpath resource every producer
 * (restaurant/carwash/barbershop) and finance's consumer already resolve — there is exactly one
 * physical copy of this file in the repo (see docs/EVENT-CATALOG.md's {@code SaleRecorded} entry).
 *
 * <p>All Phase-4 (ADR 0027) loyalty/gift-card fields are nullable unions; a legacy/pre-Phase-4
 * producer's bytes decode with every one of them {@code null} (Avro schema resolution — the current
 * reader schema is byte-identical to what old producers already wrote, since the five fields simply
 * default to {@code null} when absent from the writer, per Avro's forward-compatible decode rules).
 */
public final class SaleRecordedConsumerSchema {

  public static final String RESOURCE = "avro/SaleRecorded.avsc";
  public static final String TOPIC = "SaleRecorded";

  private static final Schema SCHEMA = parse();

  private SaleRecordedConsumerSchema() {
    // static holder
  }

  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw {@code SaleRecorded} Avro bytes into a {@link SaleRecordedFact}.
   *
   * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload
   * @param payload the raw Avro bytes off the topic
   */
  public static SaleRecordedFact decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    return new SaleRecordedFact(
        eventId,
        UUID.fromString(str(record, "sale_id")),
        str(record, "company_id"),
        UUID.fromString(str(record, "business_id")),
        (long) record.get("amount_minor"),
        str(record, "currency"),
        Instant.ofEpochMilli((long) record.get("occurred_at")),
        longOrNull(record, "subtotal_minor"),
        longOrNull(record, "discount_minor"),
        uuidOrNull(record, "loyalty_member_id"),
        longOrNull(record, "loyalty_redeemed_points"),
        longOrNull(record, "loyalty_redeemed_minor"),
        uuidOrNull(record, "gift_card_id"),
        longOrNull(record, "gift_card_redeemed_minor"));
  }

  private static String str(GenericRecord record, String field) {
    Object value = record.get(field);
    return value == null ? null : value.toString();
  }

  private static Long longOrNull(GenericRecord record, String field) {
    Object value = record.get(field);
    return value == null ? null : (Long) value;
  }

  private static UUID uuidOrNull(GenericRecord record, String field) {
    String value = str(record, field);
    return value == null ? null : UUID.fromString(value);
  }

  private static Schema parse() {
    try (InputStream in =
        SaleRecordedConsumerSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
