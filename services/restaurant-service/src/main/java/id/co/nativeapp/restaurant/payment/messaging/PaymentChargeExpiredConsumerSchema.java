package id.co.nativeapp.restaurant.payment.messaging;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads restaurant-service's CONSUMER copy of the {@code PaymentChargeExpired} Avro schema from the
 * classpath ({@code avro/PaymentChargeExpired.avsc}, the single {@code libs/contracts} source of
 * truth, ADR 0003) and decodes raw Avro bytes into a {@link PaymentChargeExpiredEvent}. Mirrors
 * {@link PaymentChargeSucceededConsumerSchema}.
 */
public final class PaymentChargeExpiredConsumerSchema {

  public static final String RESOURCE = "avro/PaymentChargeExpired.avsc";
  public static final String TOPIC = "PaymentChargeExpired";

  private PaymentChargeExpiredConsumerSchema() {}

  private static final class Holder {
    private static final Schema SCHEMA = parse();

    private Holder() {}
  }

  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * Decodes raw {@code PaymentChargeExpired} Avro bytes.
   *
   * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload
   * @param payload the raw Avro bytes off the topic
   */
  public static PaymentChargeExpiredEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.SCHEMA);
    Object referenceIdRaw = record.get("reference_id");
    return new PaymentChargeExpiredEvent(
        eventId,
        UUID.fromString(record.get("charge_id").toString()),
        record.get("company_id").toString(),
        record.get("vertical").toString(),
        UUID.fromString(record.get("payment_id").toString()),
        referenceIdRaw == null ? null : UUID.fromString(referenceIdRaw.toString()),
        UUID.fromString(record.get("business_id").toString()),
        (long) record.get("amount_minor"),
        record.get("currency").toString(),
        record.get("reason").toString(),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        PaymentChargeExpiredConsumerSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
