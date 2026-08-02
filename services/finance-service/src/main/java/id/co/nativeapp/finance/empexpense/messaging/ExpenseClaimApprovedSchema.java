package id.co.nativeapp.finance.empexpense.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's reader view of the {@code ExpenseClaimApproved} contract from the
 * classpath ({@code avro/ExpenseClaimApproved.avsc}, single-sourced from libs/contracts — ADR
 * 0003). Consumed to recognise the expense at approval: Dr the category expense (resolved from
 * {@code gl_hint} via the effective {@code mapping_rule}, suspense fail-safe) / Cr {@code 2600
 * Employee Expense Payable} (ADR 0030). Wire bytes are raw Avro (libs/events {@code AvroSerde}),
 * deduped by the event UUID — no Schema Registry serde.
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

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into an {@link
   * ExpenseClaimApprovedEvent}, using this consumer copy of the schema as both writer and reader
   * schema. The money is reconstructed as {@code libs/money} {@link Money} from the integer {@code
   * amount_minor} + ISO-4217 {@code currency} (never a float); {@code approved_at} is epoch millis
   * UTC and {@code expense_date} is epoch days (the {@code AssignmentChanged}/{@code
   * GroupMembershipChanged} idiom).
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static ExpenseClaimApprovedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    LocalDate expenseDate = LocalDate.ofEpochDay(((Number) record.get("expense_date")).longValue());
    return new ExpenseClaimApprovedEvent(
        eventId,
        UUID.fromString(record.get("claim_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("org_unit_id").toString()),
        UUID.fromString(record.get("employee_id").toString()),
        amount,
        record.get("gl_hint").toString(),
        expenseDate,
        Instant.ofEpochMilli((long) record.get("approved_at")));
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
