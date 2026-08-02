package id.co.nativeapp.finance.empexpense.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads finance-service's reader view of the {@code ExpenseClaimVoided} contract from the classpath
 * ({@code avro/ExpenseClaimVoided.avsc}, single-sourced from libs/contracts — ADR 0003). Consumed
 * to post the exact contra of a voided approval: Dr {@code 2600 Employee Expense Payable} / Cr the
 * same category expense — the mapping is resolved effective at the ORIGINAL {@code approved_at},
 * the entry posts into the period of {@code voided_at} (ADR 0030). A void arriving after a
 * settlement is a loud logged skip, never a silent reversal of moved money.
 *
 * <p>The {@code decode(...)} + event record land with the listener (E2).
 */
public final class ExpenseClaimVoidedSchema {

  /** Classpath location of the {@code .avsc}. */
  public static final String RESOURCE = "avro/ExpenseClaimVoided.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "ExpenseClaimVoided";

  private static final Schema SCHEMA = parse();

  private ExpenseClaimVoidedSchema() {
    // static holder
  }

  /** The parsed reader schema. */
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
