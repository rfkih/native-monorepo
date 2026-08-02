package id.co.nativeapp.finance.empexpense.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads finance-service's reader view of the {@code ExpenseClaimApproved} contract from the
 * classpath ({@code avro/ExpenseClaimApproved.avsc}, single-sourced from libs/contracts — ADR
 * 0003). Consumed to recognise the expense at approval: Dr the category expense (resolved from
 * {@code gl_hint} via the effective {@code mapping_rule}, suspense fail-safe) / Cr {@code 2600
 * Employee Expense Payable} (ADR 0030). Wire bytes are raw Avro (libs/events {@code AvroSerde}),
 * deduped by the event UUID — no Schema Registry serde.
 *
 * <p>The {@code decode(...)} + event record land with the listener (E2).
 */
public final class ExpenseClaimApprovedSchema {

  /** Classpath location of the {@code .avsc}. */
  public static final String RESOURCE = "avro/ExpenseClaimApproved.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "ExpenseClaimApproved";

  private static final Schema SCHEMA = parse();

  private ExpenseClaimApprovedSchema() {
    // static holder
  }

  /** The parsed reader schema. */
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
