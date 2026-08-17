package id.co.nativeapp.restaurant.inventory.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads the {@code StockReceived} Avro schema ({@code avro/StockReceived.avsc}, single-sourced from
 * libs/contracts — ADR 0003) — no Avro code-gen (the codebase convention). Will be emitted when a
 * PRICED goods receipt is recorded (a moving-average receive, ADR 0056) so finance-service can
 * capitalize the landed value to Inventory (ADR 0067 §1, "{@code StockReceived}"). {@code
 * ingredient_id} is a UUID reference, not PII. Money is integer minor units + ISO-4217, never a
 * float (rule 8).
 *
 * <p>ADR 0067 Phase 0 (contracts-first, this class): schema registration + catalog + contract tests
 * only. The {@code toRecord(...)} builder and the {@code goods_receipt} idempotency anchor land
 * with {@code inventory.service.IngredientWriter}'s priced-receive branch (Phase B) — no outbox row
 * is written by this Phase.
 */
public final class StockReceivedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/StockReceived.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "StockReceived";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "goods_receipt";

  private static final Schema SCHEMA = parse();

  private StockReceivedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  private static Schema parse() {
    try (InputStream in =
        StockReceivedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
