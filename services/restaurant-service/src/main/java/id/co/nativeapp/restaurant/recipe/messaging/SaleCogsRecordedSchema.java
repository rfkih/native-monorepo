package id.co.nativeapp.restaurant.recipe.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code SaleCogsRecorded} Avro schema ({@code avro/SaleCogsRecorded.avsc},
 * single-sourced from libs/contracts — ADR 0003) — no Avro code-gen (the codebase convention). Will
 * be emitted when a sale depletes recipe ingredients (ADR 0050 phase C) so finance-service can post
 * perpetual COGS (ADR 0067 §1, "{@code SaleCogsRecorded}"). Deliberately a SEPARATE event from
 * {@code SaleRecorded} (not a field on it) to avoid the SALE posting-template deployment hazard
 * (ADR 0050 phase-C pin, V37 note). Money is integer minor units + ISO-4217, never a float (rule
 * 8).
 *
 * <p>ADR 0067 Phase C: {@link #toRecord} builds the payload written by {@code
 * recipe.service.IngredientDepletionWriter}'s COGS fold, via {@code sale.service.SaleWriter}, in
 * the same transaction as the sale + depletion. Emitted ONLY when the fold is positive — a sale
 * with no costed recipe depletion writes no event (mirrors {@code sale.cogs_minor}/{@code
 * cogs_currency} staying NULL).
 */
public final class SaleCogsRecordedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/SaleCogsRecorded.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "SaleCogsRecorded";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "sale";

  private static final Schema SCHEMA = parse();

  private SaleCogsRecordedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code SaleCogsRecorded} record. {@code cogsMinor} is the exact Σ (depleted qty ×
   * moving-average unit cost) fold ({@link
   * id.co.nativeapp.restaurant.recipe.service.IngredientDepletionWriter.CogsResult}), never a float
   * (rule 8); callers must only invoke this when {@code cogsMinor > 0}.
   *
   * @param saleId the sale aggregate id (partition key + finance idempotency key)
   * @param companyId the owning tenant
   * @param businessId the originating outlet — the dimensional {@code business_id} finance stamps
   *     on the COGS {@code ledger_posting}
   * @param occurredAt when the sale occurred — drives the accounting period (same period as the
   *     sale's revenue)
   * @param cogsMinor Σ depleted qty × moving-average unit cost, minor units
   * @param currency ISO-4217 code of {@code cogsMinor}
   */
  public static GenericRecord toRecord(
      UUID saleId,
      String companyId,
      UUID businessId,
      Instant occurredAt,
      long cogsMinor,
      String currency) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("sale_id", saleId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("occurred_at", occurredAt.toEpochMilli());
    record.put("cogs_minor", cogsMinor);
    record.put("currency", currency);
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        SaleCogsRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
