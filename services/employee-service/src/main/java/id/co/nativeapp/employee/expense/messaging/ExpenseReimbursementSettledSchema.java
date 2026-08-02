package id.co.nativeapp.employee.expense.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads the {@code ExpenseReimbursementSettled} Avro schema ({@code
 * avro/ExpenseReimbursementSettled.avsc}, single-sourced from libs/contracts — ADR 0003). Emitted
 * when an APPROVED claim is reimbursed: {@code settlement_kind=DIRECT} (pay-now, E4) or {@code
 * PAYROLL} (the claim rode a POSTED payroll run's payslip — one event per claim in the
 * CALCULATED→POSTED transaction, E5). Finance settles the payable ONCE per claim; supersession
 * re-emissions no-op on the finance guard row (ADR 0030).
 *
 * <p>The {@code toRecord(...)} builders land with the pay-now writer (E4) and the payroll linker
 * (E5).
 */
public final class ExpenseReimbursementSettledSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/ExpenseReimbursementSettled.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "ExpenseReimbursementSettled";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "expense_claim";

  /** {@code settlement_kind} value: paid immediately by a manager (AP-payment style). */
  public static final String KIND_DIRECT = "DIRECT";

  /** {@code settlement_kind} value: settled by a POSTED payroll run. */
  public static final String KIND_PAYROLL = "PAYROLL";

  private static final Schema SCHEMA = parse();

  private ExpenseReimbursementSettledSchema() {
    // static holder
  }

  /** The parsed reader/writer schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  private static Schema parse() {
    try (InputStream in =
        ExpenseReimbursementSettledSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
