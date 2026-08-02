package id.co.nativeapp.employee.expense.messaging;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Objects;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code ExpenseReimbursementSettled} Avro schema ({@code
 * avro/ExpenseReimbursementSettled.avsc}, single-sourced from libs/contracts — ADR 0003). Emitted
 * when an APPROVED claim is reimbursed: {@code settlement_kind=DIRECT} (pay-now, E4) or {@code
 * PAYROLL} (the claim rode a POSTED payroll run's payslip — one event per claim in the
 * CALCULATED→POSTED transaction, E5). Finance settles the payable ONCE per claim; supersession
 * re-emissions no-op on the finance guard row (ADR 0030).
 *
 * <p>{@link #toRecordDirect} lands with the pay-now writer (E4); the payroll linker's builder
 * (carrying {@code payroll_run_id}/{@code run_seq}) lands with E5.
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

  /**
   * Builds an {@code ExpenseReimbursementSettled} record with {@code settlement_kind=DIRECT} — the
   * pay-now path (E4, ADR 0030 §6). {@code payroll_run_id}/{@code run_seq} are explicitly written
   * as {@code null} (the union-with-default idiom; old readers ignore them).
   *
   * @param claim a claim whose status is already {@code REIMBURSED} (the caller mutates before
   *     building the record, in the same transaction — rule 3)
   * @param settledAt the settlement instant; must equal {@code claim.getSettledAt()}
   */
  public static GenericRecord toRecordDirect(ExpenseClaim claim, Instant settledAt) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(settledAt, "settledAt");
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("claim_id", claim.getId().toString());
    record.put("company_id", claim.getCompanyId());
    record.put("org_unit_id", claim.getOrgUnitId().toString());
    record.put("employee_id", claim.getEmployeeId().toString());
    record.put("amount_minor", claim.getAmount().amountMinor());
    record.put("currency", claim.getAmount().currency().getCurrencyCode());
    record.put("settlement_kind", KIND_DIRECT);
    record.put("payroll_run_id", null);
    record.put("run_seq", null);
    record.put("settled_at", settledAt.toEpochMilli());
    return record;
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
