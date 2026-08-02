package id.co.nativeapp.employee.expense.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads the {@code ExpenseClaimApproved} Avro schema ({@code avro/ExpenseClaimApproved.avsc},
 * single-sourced from libs/contracts — ADR 0003) — no Avro code-gen (the codebase convention).
 * Emitted when a manager APPROVES an expense claim; expense recognition happens at approval (ADR
 * 0030). Carries {@code employee_id} as a UUID reference (not PII — a claim amount derives nothing
 * about compensation); merchant/note/receipt never cross an event. Money is integer minor units +
 * ISO-4217, never a float (rule 6/8).
 *
 * <p>The {@code toRecord(...)} builder lands with the {@code ExpenseClaim} aggregate (E1).
 */
public final class ExpenseClaimApprovedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/ExpenseClaimApproved.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "ExpenseClaimApproved";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "expense_claim";

  private static final Schema SCHEMA = parse();

  private ExpenseClaimApprovedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  private static Schema parse() {
    try (InputStream in =
        ExpenseClaimApprovedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
