package id.co.nativeapp.employee.expense.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads the {@code ExpenseClaimVoided} Avro schema ({@code avro/ExpenseClaimVoided.avsc},
 * single-sourced from libs/contracts — ADR 0003). Emitted when an APPROVED, un-settled, un-linked
 * claim is VOIDED — the correction path; finance posts the exact contra, resolving the mapping
 * effective at the ORIGINAL {@code approved_at} while posting into the period of {@code voided_at}
 * (ADR 0030). The producer guards that a settled or payroll-linked claim can never void.
 *
 * <p>The {@code toRecord(...)} builder lands with the void transition (E7).
 */
public final class ExpenseClaimVoidedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/ExpenseClaimVoided.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "ExpenseClaimVoided";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "expense_claim";

  private static final Schema SCHEMA = parse();

  private ExpenseClaimVoidedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  private static Schema parse() {
    try (InputStream in =
        ExpenseClaimVoidedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
