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
 * Loads the {@code ExpenseClaimApproved} Avro schema ({@code avro/ExpenseClaimApproved.avsc},
 * single-sourced from libs/contracts — ADR 0003) — no Avro code-gen (the codebase convention).
 * Emitted when a manager APPROVES an expense claim; expense recognition happens at approval (ADR
 * 0030). Carries {@code employee_id} as a UUID reference (not PII — a claim amount derives nothing
 * about compensation); merchant/note/receipt never cross an event. Money is integer minor units +
 * ISO-4217, never a float (rule 6/8).
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

  /**
   * Builds an {@code ExpenseClaimApproved} record from a just-APPROVED claim. {@code glHint} is the
   * claim's category's {@code gl_hint} AT approval time (resolved by the caller — the aggregate
   * itself does not know its category's current hint); {@code expense_date} encodes as epoch days
   * (the Avro {@code date} logical type, the {@code AssignmentChanged} idiom), {@code approved_at}
   * as epoch millis.
   *
   * @param claim a claim whose status is already {@code APPROVED} (the caller mutates before
   *     building the record, in the same transaction — rule 3)
   * @param glHint the category's {@code gl_hint}; never null (empty string = general)
   * @param approvedAt the approval instant; must equal {@code claim.getApprovedAt()}
   */
  public static GenericRecord toRecord(ExpenseClaim claim, String glHint, Instant approvedAt) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(glHint, "glHint");
    Objects.requireNonNull(approvedAt, "approvedAt");
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("claim_id", claim.getId().toString());
    record.put("company_id", claim.getCompanyId());
    record.put("org_unit_id", claim.getOrgUnitId().toString());
    record.put("employee_id", claim.getEmployeeId().toString());
    record.put("amount_minor", claim.getAmount().amountMinor());
    record.put("currency", claim.getAmount().currency().getCurrencyCode());
    record.put("gl_hint", glHint);
    record.put("expense_date", (int) claim.getExpenseDate().toEpochDay());
    record.put("approved_at", approvedAt.toEpochMilli());
    return record;
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
