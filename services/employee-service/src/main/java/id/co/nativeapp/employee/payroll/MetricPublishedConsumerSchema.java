package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * employee-service's CONSUMER copy of carwash-service's {@code MetricPublished} schema ({@code
 * avro/MetricPublished.avsc}, full name {@code id.co.nativeapp.events.carwash.MetricPublished}).
 * Decodes raw Avro bytes (no Schema Registry serde) into a {@link MetricProjectedEvent}. A contract
 * test asserts this copy stays backward-compatible with the producer schema (rule 7).
 *
 * <p>The producer payload carries NO {@code company_id} (it is an outbox-row column the Debezium
 * outbox router stamps as a Kafka header); the listener reads the tenant from that header and
 * passes it in here.
 */
public final class MetricPublishedConsumerSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/MetricPublished.avsc";

  /** The {@code MetricPublished} Kafka topic. */
  public static final String TOPIC = "MetricPublished";

  private MetricPublishedConsumerSchema() {
    // static holder
  }

  private static final class Holder {
    private static final Schema SCHEMA = parse();

    private Holder() {}
  }

  /** The parsed reader schema (employee's consumer copy). */
  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * Decodes raw {@code MetricPublished} bytes into the projected event.
   *
   * @param eventId the event UUID (idempotency key, from the {@code id} header)
   * @param companyId the tenant (from the {@code company_id} header — not in the payload)
   * @param payload the raw Avro bytes
   */
  public static MetricProjectedEvent decode(UUID eventId, String companyId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.SCHEMA);
    return new MetricProjectedEvent(
        eventId,
        companyId,
        record.get("metric_key").toString(),
        record.get("period").toString(),
        record.get("grain").toString(),
        UUID.fromString(record.get("subject_id").toString()),
        (long) record.get("value"));
  }

  private static Schema parse() {
    try (InputStream in =
        MetricPublishedConsumerSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
