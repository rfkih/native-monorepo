package id.co.nativeapp.restaurant.stocktake.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code StocktakeCompleted} Avro schema from the classpath ({@code
 * avro/StocktakeCompleted.avsc}) and builds {@link GenericRecord}s from it — no Avro
 * code-generation, matching every other schema holder. The schema is single-sourced in {@code
 * libs/contracts} (ADR 0003) and registered in {@code docs/EVENT-CATALOG.md}.
 *
 * <p>Emitted when a physical inventory stocktake (ADR 0038 phase 3) is submitted: finance posts
 * ONLY the SIGNED net valued shrinkage ({@code shrinkage_minor}), a single inventory-adjustment
 * entry. Every amount is integer minor units in one currency (rule 8). The producer computes {@code
 * shrinkage_minor} server-side as {@code Σ (system_qty − counted_qty) × unit_cost_minor} over
 * cost-bearing lines.
 */
public final class StocktakeCompletedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/StocktakeCompleted.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "StocktakeCompleted";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "stocktake";

  private static final Schema SCHEMA = parse();

  private StocktakeCompletedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code StocktakeCompleted}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code StocktakeCompleted} record. {@code shrinkageMinor} is minor units in {@code
   * currency}.
   *
   * @param stocktakeId the stocktake aggregate id (partition key)
   * @param companyId the owning tenant
   * @param businessId the outlet the count was taken at
   * @param countedAt when the stocktake was submitted
   * @param shrinkageMinor SIGNED net valued shrinkage: positive = net loss, negative = net gain,
   *     zero = no entry
   * @param currency ISO-4217 code of {@code shrinkageMinor}
   */
  public static GenericRecord toRecord(
      UUID stocktakeId,
      String companyId,
      UUID businessId,
      Instant countedAt,
      long shrinkageMinor,
      String currency) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("stocktake_id", stocktakeId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("counted_at", countedAt.toEpochMilli());
    record.put("shrinkage_minor", shrinkageMinor);
    record.put("currency", currency);
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        StocktakeCompletedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
