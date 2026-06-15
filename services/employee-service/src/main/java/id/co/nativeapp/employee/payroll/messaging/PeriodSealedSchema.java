package id.co.nativeapp.employee.payroll.messaging;

import id.co.nativeapp.employee.payroll.dto.PeriodSealedProjectedEvent;
import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * employee-service's CONSUMER copy of {@code PeriodSealed} ({@code avro/PeriodSealed.avsc}, full
 * name {@code id.co.nativeapp.events.carwash.PeriodSealed}). Decodes raw Avro bytes into a {@link
 * PeriodSealedProjectedEvent}; also builds a record (used by the contract test / a test producer).
 * The payload carries {@code company_id} (the consumer binds the projection write to it for RLS). A
 * contract test asserts backward-compatible evolution (rule 7).
 */
public final class PeriodSealedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/PeriodSealed.avsc";

  /** The {@code PeriodSealed} Kafka topic. */
  public static final String TOPIC = "PeriodSealed";

  private PeriodSealedSchema() {
    // static holder
  }

  private static final class Holder {
    private static final Schema SCHEMA = parse();

    private Holder() {}
  }

  /** The parsed reader schema. */
  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /** Builds a {@code PeriodSealed} record (for the contract test / a test producer). */
  public static GenericRecord toRecord(
      String companyId, UUID businessId, String period, Instant sealedAt) {
    GenericRecord record = new GenericData.Record(Holder.SCHEMA);
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("period", period);
    record.put("sealed_at", sealedAt.toEpochMilli());
    return record;
  }

  /**
   * Decodes raw {@code PeriodSealed} bytes into the projected event.
   *
   * @param eventId the event UUID (idempotency key, from the {@code id} header)
   * @param payload the raw Avro bytes
   */
  public static PeriodSealedProjectedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.SCHEMA);
    return new PeriodSealedProjectedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        record.get("period").toString(),
        Instant.ofEpochMilli((long) record.get("sealed_at")));
  }

  private static Schema parse() {
    try (InputStream in = PeriodSealedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
