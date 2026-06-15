package id.co.nativeapp.employee.payroll.messaging;

import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code PayrollPosted} Avro schema ({@code avro/PayrollPosted.avsc}) and builds {@link
 * GenericRecord}s from a posted {@link PayrollRun} — no Avro code-gen (the codebase convention).
 * Company-level TOTALS only; NO PII (rule 6). Carries the frozen rule-versions set and the runtime
 * illustrative flag.
 */
public final class PayrollPostedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/PayrollPosted.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "PayrollPosted";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "payroll_run";

  private PayrollPostedSchema() {
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

  /** One {@code (rule_key, rule_version)} entry of the frozen set. */
  public record RuleVersion(String ruleKey, String ruleVersion) {}

  /** Builds a {@code PayrollPosted} record from a posted run + its frozen rule-version set. */
  public static GenericRecord toRecord(PayrollRun run, List<RuleVersion> ruleVersions) {
    GenericRecord record = new GenericData.Record(Holder.SCHEMA);
    record.put("payroll_run_id", run.getId().toString());
    record.put("company_id", run.getCompanyId());
    record.put("period", run.getPeriod());
    record.put("base_currency", run.getBaseCurrency());
    record.put("gross_total_minor", run.getGrossTotal().amountMinor());
    record.put("employee_deduction_total_minor", run.getEmployeeDeductionTotal().amountMinor());
    record.put(
        "employer_contribution_total_minor", run.getEmployerContributionTotal().amountMinor());
    record.put("net_total_minor", run.getNetTotal().amountMinor());

    Schema ruleVersionsField = Holder.SCHEMA.getField("rule_versions").schema();
    Schema ruleVersionRecord = ruleVersionsField.getElementType();
    List<GenericRecord> rvRecords =
        ruleVersions.stream()
            .map(
                rv -> {
                  GenericRecord r = new GenericData.Record(ruleVersionRecord);
                  r.put("rule_key", rv.ruleKey());
                  r.put("rule_version", rv.ruleVersion());
                  return (GenericRecord) r;
                })
            .toList();
    record.put("rule_versions", rvRecords);

    record.put("run_seq", run.getRunSeq());
    record.put("uses_illustrative_rules", run.usesIllustrativeRules());
    record.put("posted_at", run.getPostedAt().toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        PayrollPostedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
