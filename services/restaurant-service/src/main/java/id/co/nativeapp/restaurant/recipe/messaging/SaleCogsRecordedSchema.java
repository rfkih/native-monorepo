package id.co.nativeapp.restaurant.recipe.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads the {@code SaleCogsRecorded} Avro schema ({@code avro/SaleCogsRecorded.avsc},
 * single-sourced from libs/contracts — ADR 0003) — no Avro code-gen (the codebase convention). Will
 * be emitted when a sale depletes recipe ingredients (ADR 0050 phase C) so finance-service can post
 * perpetual COGS (ADR 0067 §1, "{@code SaleCogsRecorded}"). Deliberately a SEPARATE event from
 * {@code SaleRecorded} (not a field on it) to avoid the SALE posting-template deployment hazard
 * (ADR 0050 phase-C pin, V37 note). Money is integer minor units + ISO-4217, never a float (rule
 * 8).
 *
 * <p>ADR 0067 Phase 0 (contracts-first, this class): schema registration + catalog + contract tests
 * only. The {@code toRecord(...)} builder, {@code sale.cogs_minor}/{@code cogs_currency}
 * persistence, and the outbox write land with {@code recipe.service.IngredientDepletionWriter}
 * (Phase C) — no outbox row is written by this Phase.
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
