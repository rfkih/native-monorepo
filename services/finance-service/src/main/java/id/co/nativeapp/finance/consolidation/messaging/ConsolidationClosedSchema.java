package id.co.nativeapp.finance.consolidation.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's PRODUCER copy of the {@code ConsolidationClosed} Avro schema (the SOURCE
 * OF TRUTH; P3d SEAM 4a) from the classpath ({@code avro/ConsolidationClosed.avsc}) and builds
 * {@link GenericRecord}s from it — no Avro code-generation plugin and no Schema Registry serde, the
 * same approach as {@link id.co.nativeapp.finance.group.messaging.GroupDefinedSchema} (a producer)
 * and the finance consumer schemas. The notification-service consumer keeps its own back-compatible
 * copy; its {@code ConsolidationClosedContractTest} asserts the two stay compatible (rule 7).
 *
 * <p>The nullable {@code group_id} DISTINGUISHES the two close kinds: a within-company close emits
 * {@code group_id = null} (the company finalised its own period); a GROUP close emits {@code
 * group_id = G} with {@code company_id = the group's lead}.
 */
public final class ConsolidationClosedSchema {

  /** Classpath location of the producer {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/ConsolidationClosed.avsc";

  /** The event name as it appears in the outbox {@code event_type} column / the Kafka topic. */
  public static final String EVENT_TYPE = "ConsolidationClosed";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "consolidation";

  private static final Schema SCHEMA = parse();

  private ConsolidationClosedSchema() {
    // static holder
  }

  /** The parsed producer schema for {@code ConsolidationClosed}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code ConsolidationClosed} {@link GenericRecord}.
   *
   * @param companyId the tenant the notification belongs to — the closing company for a
   *     within-company close, the group's lead for a group close
   * @param groupId the consolidation group id for a GROUP close, or {@code null} for a
   *     within-company close (the discriminator)
   * @param period the accounting period that was closed ({@code YYYY-MM})
   */
  public static GenericRecord toRecord(UUID companyId, UUID groupId, String period) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("company_id", companyId.toString());
    record.put("group_id", groupId == null ? null : groupId.toString());
    record.put("period", period);
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        ConsolidationClosedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
