package id.co.nativeapp.restaurant.metric.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the shared {@code MetricPublished} Avro schema ({@code avro/MetricPublished.avsc} from
 * libs/contracts) and builds {@link GenericRecord}s — no Avro codegen. restaurant-service is the
 * SECOND producer of this event (carwash was the first): it emits {@code sales_amount} at the
 * EMPLOYEE grain so a cashier's own sales can drive an own-sales commission. Same schema, no field
 * change (ADR 0003 single-source contract).
 *
 * <p>The metric outbox aggregate id is the sale id — each sale contributes one independent metric
 * event; the consumer accumulates by natural key and dedupes by the outbox row id.
 */
public final class MetricPublishedSchema {

  public static final String RESOURCE = "avro/MetricPublished.avsc";
  public static final String EVENT_TYPE = "MetricPublished";
  public static final String AGGREGATE_TYPE = "metric";

  private static final Schema SCHEMA = parse();

  private MetricPublishedSchema() {
    // static holder
  }

  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code MetricPublished} record.
   *
   * @param metricKey the metric (e.g. {@code sales_amount})
   * @param period the period the metric covers ({@code YYYY-MM-DD})
   * @param grain the grain ({@code employee | shift | outlet})
   * @param subjectId the grain subject id (UUID as string — the cashier's Keycloak sub at employee
   *     grain)
   * @param value the metric value (a long; the net sale amount in minor units)
   * @param sourceBusinessId the outlet the sale originated from (UUID as string)
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
