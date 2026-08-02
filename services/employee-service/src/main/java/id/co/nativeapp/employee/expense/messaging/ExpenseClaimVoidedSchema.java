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
 * Loads the {@code ExpenseClaimVoided} Avro schema ({@code avro/ExpenseClaimVoided.avsc},
 * single-sourced from libs/contracts — ADR 0003). Emitted when an APPROVED, un-settled, un-linked
 * claim is VOIDED — the correction path; finance posts the exact contra, resolving the mapping
 * effective at the ORIGINAL {@code approved_at} while posting into the period of {@code voided_at}
 * (ADR 0030). The producer guards that a settled or payroll-linked claim can never void.
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

  /**
   * Builds an {@code ExpenseClaimVoided} record from a just-VOIDED claim (Phase E7).
   *
   * <p><strong>{@code org_unit_id} (E2 reviewer's S2 note, binding).</strong> {@code
   * claim.getOrgUnitId()} is the claim row's own SNAPSHOT (taken at create/edit time, ADR 0030 §
   * "org_unit_id"; see {@code ExpenseClaim}'s class Javadoc) — it must NEVER be re-resolved against
   * the employee's CURRENT assignment here. The void's contra has to hit the exact same {@code
   * business_id} the original approval posted under, and an employee's assignment can change
   * between approval and void; re-resolving would silently shift the dimensional attribution of a
   * pure correction entry. Passing the snapshot straight through (never calling anything
   * assignment-resolving) is what satisfies that.
   *
   * <p><strong>{@code gl_hint} (documented divergence risk).</strong> {@code glHint} is the
   * category's {@code gl_hint} resolved by the CALLER at void time — {@code ExpenseClaimWriter}
   * re-reads {@code ExpenseCategory#getGlHint()} the SAME way {@code approve} does, because the
   * claim row itself never stored the hint that was current AT approval (no {@code
   * gl_hint_at_approval} column exists — deliberately not added for this one field, Phase E7). In
   * the overwhelming common case this is identical to the hint the approval actually carried, since
   * a category's hint rarely changes. It diverges ONLY if an admin edits the category's {@code
   * gl_hint} in the window between this claim's approval and its void — a rare admin action. Even
   * in that window the books stay CORRECT, not merely "close": finance's {@code
   * ExpenseClaimVoidWriter} resolves the posting account via {@code
   * GlAccountResolver#resolveExpense(glHint, approvedAt)} — the {@code mapping_rule} effective AT
   * the ORIGINAL {@code approved_at} — so the contra lands on the account the mapping-at-that-time
   * says the hint was pointing at then; only a hint value that changed AND whose old mapping row
   * would have resolved differently under the NEW hint string could misfire, an edge narrow enough
   * (and gated behind an intentional admin edit, not routine drift) that re-resolving the current
   * hint here — rather than adding a column solely to freeze it — is the accepted v1 trade-off (ADR
   * 0030 §5 addendum).
   *
   * @param claim a claim whose status is already {@code VOIDED} (the caller mutates before building
   *     the record, in the same transaction — rule 3); {@code claim.getApprovedAt()} must be
   *     non-null (guaranteed once a claim has ever been APPROVED, the only path to VOIDED)
   * @param glHint the category's {@code gl_hint} AT VOID TIME (resolved by the caller); never null
   * @param voidedAt the void instant; must equal {@code claim.getDecidedAt()}
   */
  public static GenericRecord toRecord(ExpenseClaim claim, String glHint, Instant voidedAt) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(glHint, "glHint");
    Objects.requireNonNull(voidedAt, "voidedAt");
    Instant approvedAt = Objects.requireNonNull(claim.getApprovedAt(), "claim.approvedAt");
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("claim_id", claim.getId().toString());
    record.put("company_id", claim.getCompanyId());
    record.put("org_unit_id", claim.getOrgUnitId().toString());
    record.put("employee_id", claim.getEmployeeId().toString());
    record.put("amount_minor", claim.getAmount().amountMinor());
    record.put("currency", claim.getAmount().currency().getCurrencyCode());
    record.put("gl_hint", glHint);
    record.put("approved_at", approvedAt.toEpochMilli());
    record.put("voided_at", voidedAt.toEpochMilli());
    return record;
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
