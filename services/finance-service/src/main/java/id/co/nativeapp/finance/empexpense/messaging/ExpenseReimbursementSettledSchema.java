package id.co.nativeapp.finance.empexpense.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads finance-service's reader view of the {@code ExpenseReimbursementSettled} contract from the
 * classpath ({@code avro/ExpenseReimbursementSettled.avsc}, single-sourced from libs/contracts —
 * ADR 0003). Consumed to settle the employee-expense payable: Dr {@code 2600} / Cr CASH_CLEARING —
 * a balance-sheet move only. SETTLE-ONCE: a per-claim guard row ({@code
 * employee_expense_settlement}, UNIQUE company_id+claim_id) makes any second settlement — Kafka
 * re-delivery or a payroll-supersession re-emission — a logged no-op (ADR 0030).
 *
 * <p>The {@code decode(...)} + event record land with the listener (E2).
 */
public final class ExpenseReimbursementSettledSchema {

  /** Classpath location of the {@code .avsc}. */
  public static final String RESOURCE = "avro/ExpenseReimbursementSettled.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "ExpenseReimbursementSettled";

  private static final Schema SCHEMA = parse();

  private ExpenseReimbursementSettledSchema() {
    // static holder
  }

  /** The parsed reader schema. */
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
