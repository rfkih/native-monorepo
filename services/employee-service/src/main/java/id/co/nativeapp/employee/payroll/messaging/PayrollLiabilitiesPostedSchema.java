package id.co.nativeapp.employee.payroll.messaging;

import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code PayrollLiabilitiesPosted} Avro schema ({@code
 * avro/PayrollLiabilitiesPosted.avsc}, single-sourced from {@code libs/contracts} — ADR 0003) and
 * builds {@link GenericRecord}s from a posted {@link PayrollRun} + its computed liability buckets
 * (ADR 0032, Track P phase P4) — no Avro code-gen (the codebase convention). Emitted THIRD in the
 * {@code post()} outbox transaction, after {@code PayrollPosted} and every {@code
 * LaborCostAllocated} bucket. Company-level bucket TOTALS only; NO PII (rule 6).
 */
public final class PayrollLiabilitiesPostedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/PayrollLiabilitiesPosted.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "PayrollLiabilitiesPosted";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "payroll_run";

  private PayrollLiabilitiesPostedSchema() {
    // static holder
  }

  private static final class Holder {
    private static final Schema SCHEMA = parse();

    private Holder() {}
  }

  /** The parsed reader/writer schema. */
  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * One liability bucket: {@code liability_role} is one of {@code NET_WAGES_PAYABLE}, {@code
   * PPH21_PAYABLE}, {@code BPJS_KES_PAYABLE}, {@code BPJS_TK_PAYABLE}, {@code
   * OTHER_DEDUCTIONS_PAYABLE} (the finance {@code AccountRole} names). {@code amount} is normally
   * positive (a Cr leg for finance); it MAY be negative for {@code PPH21_PAYABLE} in the December
   * Art-17 true-up refund month (ADR 0031 Track P phase P3) — finance posts a negative bucket as a
   * Dr leg for its absolute value instead (ADR 0032).
   */
  public record LiabilityBucket(String liabilityRole, Money amount) {}

  /**
   * Builds a {@code PayrollLiabilitiesPosted} record from a posted run, its total employer-borne
   * labor cost, and its non-zero liability buckets. {@code run_type} is {@code run.getRunType()} —
   * REGULAR or THR (Track P Phase P8, ADR 0035).
   *
   * @param employerCostTotal {@code gross_total_minor + employer_contribution_total_minor} — the
   *     SAME sum {@code LaborCostAllocated}'s buckets total to; the Dr leg of finance's liability
   *     entry
   * @param liabilities the non-zero liability buckets (a zero-amount bucket is omitted by the
   *     caller before this method is invoked)
   */
  public static GenericRecord toRecord(
      PayrollRun run, Money employerCostTotal, List<LiabilityBucket> liabilities) {
    GenericRecord record = new GenericData.Record(Holder.SCHEMA);
    record.put("payroll_run_id", run.getId().toString());
    record.put("company_id", run.getCompanyId());
    record.put("period", run.getPeriod());
    record.put("run_seq", run.getRunSeq());
    record.put("run_type", run.getRunType().name());
    record.put("base_currency", run.getBaseCurrency());
    record.put("employer_cost_total_minor", employerCostTotal.amountMinor());

    Schema liabilitiesField = Holder.SCHEMA.getField("liabilities").schema();
    Schema bucketRecordSchema = liabilitiesField.getElementType();
    List<GenericRecord> bucketRecords =
        liabilities.stream()
            .map(
                bucket -> {
                  GenericRecord r = new GenericData.Record(bucketRecordSchema);
                  r.put("liability_role", bucket.liabilityRole());
                  r.put("amount_minor", bucket.amount().amountMinor());
                  return (GenericRecord) r;
                })
            .toList();
    record.put("liabilities", bucketRecords);

    record.put("uses_illustrative_rules", run.usesIllustrativeRules());
    record.put("posted_at", requirePostedAt(run).toEpochMilli());
    return record;
  }

  private static Instant requirePostedAt(PayrollRun run) {
    Instant postedAt = run.getPostedAt();
    if (postedAt == null) {
      throw new IllegalStateException(
          "payroll_run " + run.getId() + " has no posted_at yet — post() must stamp it first");
    }
    return postedAt;
  }

  private static Schema parse() {
    try (InputStream in =
        PayrollLiabilitiesPostedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
