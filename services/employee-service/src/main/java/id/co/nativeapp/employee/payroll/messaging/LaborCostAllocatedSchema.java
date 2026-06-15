package id.co.nativeapp.employee.payroll.messaging;

import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code LaborCostAllocated} Avro schema ({@code avro/LaborCostAllocated.avsc}) and
 * builds one {@link GenericRecord} per (outlet, gl_account) bucket — AGGREGATED across employees so
 * no individual salary is derivable (rule 6). employee_id is deliberately NOT on the event (design
 * decision vs ARCHITECTURE.md §5 — see open risks).
 */
public final class LaborCostAllocatedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/LaborCostAllocated.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "LaborCostAllocated";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "payroll_run";

  private LaborCostAllocatedSchema() {
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
   * Builds a {@code LaborCostAllocated} record for one outlet/GL bucket. {@code runSeq} is the
   * run's sequence (the supersession signal finance keys on; a higher seq supersedes lower runs for
   * the same {@code (company, period)}). {@code unallocated} marks the UNALLOCATED suspense bucket
   * (employer labor cost for employees with no outlet assignment in the period) so finance can
   * clear it rather than treat it as a real outlet cost.
   */
  public static GenericRecord toRecord(
      UUID payrollRunId,
      String companyId,
      String period,
      UUID outletId,
      String glAccount,
      Money amount,
      int runSeq,
      boolean usesIllustrativeRules,
      boolean unallocated,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(Holder.SCHEMA);
    record.put("payroll_run_id", payrollRunId.toString());
    record.put("company_id", companyId);
    record.put("period", period);
    record.put("outlet_id", outletId.toString());
    record.put("gl_account", glAccount);
    record.put("amount_minor", amount.amountMinor());
    record.put("currency", amount.currency().getCurrencyCode());
    record.put("run_seq", runSeq);
    record.put("uses_illustrative_rules", usesIllustrativeRules);
    record.put("unallocated", unallocated);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        LaborCostAllocatedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
