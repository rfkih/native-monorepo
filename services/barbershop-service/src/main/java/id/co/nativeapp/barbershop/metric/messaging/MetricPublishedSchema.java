package id.co.nativeapp.barbershop.metric.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code MetricPublished} Avro schema from the classpath ({@code
 * avro/MetricPublished.avsc}) and builds {@link GenericRecord}s from it — no Avro code-generation
 * plugin. The schema is the single source of truth registered in {@code docs/EVENT-CATALOG.md};
 * this class parses it once and projects a metric onto it. The record is serialized to the outbox
 * payload via {@code libs/events} {@link id.co.nativeapp.events.AvroSerde AvroSerde}.
 *
 * <p>Field shape matches ARCHITECTURE.md §5: {@code metric_key}, {@code period}, {@code grain},
 * {@code subject_id}, {@code value} (long), {@code source_business_id}. The partition key / outbox
 * aggregate id is the {@code source_business_id} (the barbershop outlet).
 */
public final class MetricPublishedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/MetricPublished.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "MetricPublished";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "metric";

  private static final Schema SCHEMA = parse();

  private MetricPublishedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code MetricPublished}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code MetricPublished} {@link GenericRecord}.
   *
   * @param metricKey the metric this measures (e.g. {@code service_count})
   * @param period the period the metric covers (e.g. {@code YYYY-MM-DD})
   * @param grain the aggregation grain ({@code employee | shift | outlet})
   * @param subjectId the id of the grain subject (UUID as string)
   * @param value the metric value (a long; never a float)
   * @param sourceBusinessId the barbershop outlet the metric originated from (UUID as string)
   */
  public static GenericRecord toRecord(
      String metricKey,
      String period,
      String grain,
      String subjectId,
      long value,
      String sourceBusinessId) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("metric_key", metricKey);
    record.put("period", period);
    record.put("grain", grain);
    record.put("subject_id", subjectId);
    record.put("value", value);
    record.put("source_business_id", sourceBusinessId);
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        MetricPublishedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
