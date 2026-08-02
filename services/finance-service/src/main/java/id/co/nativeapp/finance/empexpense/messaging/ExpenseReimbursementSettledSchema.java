package id.co.nativeapp.finance.empexpense.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's reader view of the {@code ExpenseReimbursementSettled} contract from the
 * classpath ({@code avro/ExpenseReimbursementSettled.avsc}, single-sourced from libs/contracts —
 * ADR 0003). Consumed to settle the employee-expense payable: Dr {@code 2600} / Cr CASH_CLEARING —
 * a balance-sheet move only. SETTLE-ONCE: a per-claim guard row ({@code
 * employee_expense_claim_ledger}, UNIQUE company_id+claim_id) makes any second settlement — Kafka
 * re-delivery or a payroll-supersession re-emission — a logged no-op (ADR 0030).
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

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into an {@link
   * ExpenseReimbursementSettledEvent}, using this consumer copy of the schema as both writer and
   * reader schema. The money is reconstructed as {@code libs/money} {@link Money} from the integer
   * {@code amount_minor} + ISO-4217 {@code currency} (never a float); {@code settled_at} is epoch
   * millis UTC. {@code payroll_run_id}/{@code run_seq} are the {@code ["null", ...]}-with-default
   * union idiom — {@code null} for a DIRECT settlement (the {@code SaleRecordedSchema}
   * nullable-field decode pattern).
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static ExpenseReimbursementSettledEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    Object payrollRunIdRaw = record.get("payroll_run_id");
    UUID payrollRunId =
        payrollRunIdRaw != null ? UUID.fromString(payrollRunIdRaw.toString()) : null;
    Integer runSeq = (Integer) record.get("run_seq");
    return new ExpenseReimbursementSettledEvent(
        eventId,
        UUID.fromString(record.get("claim_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("org_unit_id").toString()),
        UUID.fromString(record.get("employee_id").toString()),
        amount,
        record.get("settlement_kind").toString(),
        payrollRunId,
        runSeq,
        Instant.ofEpochMilli((long) record.get("settled_at")));
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
