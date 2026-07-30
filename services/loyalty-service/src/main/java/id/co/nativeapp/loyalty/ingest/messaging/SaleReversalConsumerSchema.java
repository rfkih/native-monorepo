package id.co.nativeapp.loyalty.ingest.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ingest.dto.SaleReversalFact;
import id.co.nativeapp.loyalty.ingest.dto.SaleReversalFact.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads loyalty-service's CONSUMER copies of the {@code SaleVoided} / {@code SaleRefunded} Avro
 * schemas from the classpath (the single shared {@code libs/contracts} resources, ADR 0003) and
 * decodes raw Avro bytes into a unified {@link SaleReversalFact} — both events trigger the
 * IDENTICAL full-reversal behaviour (see {@link
 * id.co.nativeapp.loyalty.ingest.service.SaleReversalWriter SaleReversalWriter}).
 */
public final class SaleReversalConsumerSchema {

  public static final String VOIDED_RESOURCE = "avro/SaleVoided.avsc";
  public static final String REFUNDED_RESOURCE = "avro/SaleRefunded.avsc";
  public static final String VOIDED_TOPIC = "SaleVoided";
  public static final String REFUNDED_TOPIC = "SaleRefunded";

  private SaleReversalConsumerSchema() {
    // static holder
  }

  /**
   * Lazy holder: each {@code .avsc} is parsed on FIRST use (a parse failure is a repeatable error).
   */
  private static final class Holder {
    private static final Schema VOIDED_SCHEMA = parse(VOIDED_RESOURCE);
    private static final Schema REFUNDED_SCHEMA = parse(REFUNDED_RESOURCE);

    private Holder() {}
  }

  public static Schema voidedSchema() {
    return Holder.VOIDED_SCHEMA;
  }

  public static Schema refundedSchema() {
    return Holder.REFUNDED_SCHEMA;
  }

  /**
   * Decodes raw {@code SaleVoided} Avro bytes into a {@link SaleReversalFact}.
   *
   * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload
   */
  public static SaleReversalFact decodeVoided(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.VOIDED_SCHEMA);
    return new SaleReversalFact(
        eventId,
        Kind.VOIDED,
        UUID.fromString(record.get("sale_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  /**
   * Decodes raw {@code SaleRefunded} Avro bytes into a {@link SaleReversalFact}.
   *
   * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload
   */
  public static SaleReversalFact decodeRefunded(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.REFUNDED_SCHEMA);
    return new SaleReversalFact(
        eventId,
        Kind.REFUNDED,
        UUID.fromString(record.get("sale_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse(String resource) {
    try (InputStream in =
        SaleReversalConsumerSchema.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + resource);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + resource, e);
    }
  }
}
