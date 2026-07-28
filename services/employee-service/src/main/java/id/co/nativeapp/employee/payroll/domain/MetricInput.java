package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The local read model of consumed {@code MetricPublished} events (design §1) — variable-pay
 * inputs. Keyed by (company_id, metric_key, period, grain, subject_id) -> accumulated value. {@code
 * PER_METRIC_UNIT} / {@code PERCENT_OF_METRIC} earning rules read this projection (never a sync
 * call — rule 2). An app-maintained projection, so (like the org read model) it extends {@link
 * Auditable} (rule 4) and is RLS-scoped (rule 5); the consumer writes it bound to the EVENT's
 * company_id. No PII (a count/amount of operational activity, not individual salary).
 *
 * <p><strong>Delta semantics.</strong> Each {@code MetricPublished} value is a CONTRIBUTION the
 * consumer ACCUMULATES onto the natural-key row ({@link #applyDelta}), not a replacement — a
 * producer emits one event per source unit of activity (per wash, per sale), so a day with several
 * sales at one outlet must SUM. Accumulation is safe under the idempotency guard (each event UUID
 * is applied exactly once via {@code ProcessedEventStore}).
 */
@Entity
@Table(name = "metric_input")
public class MetricInput extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "metric_key", nullable = false, length = 64)
  private String metricKey;

  @Column(name = "period", nullable = false, length = 16)
  private String period;

  @Column(name = "grain", nullable = false, length = 16)
  private String grain;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "metric_value", nullable = false)
  private long value;

  protected MetricInput() {
    // for JPA
  }

  /** Creates a metric-input projection row with a freshly generated id. */
  public MetricInput(String metricKey, String period, String grain, UUID subjectId, long value) {
    this.id = UUID.randomUUID();
    this.metricKey = Objects.requireNonNull(metricKey, "metricKey");
    this.period = Objects.requireNonNull(period, "period");
    this.grain = Objects.requireNonNull(grain, "grain");
    this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
    this.value = value;
  }

  /**
   * Accumulates a metric contribution onto the running total for this natural key. Safe under the
   * consumer's exactly-once guard (each event UUID applies once), so per-source-unit events sum.
   */
  public void applyDelta(long delta) {
    this.value += delta;
  }

  public UUID getId() {
    return id;
  }

  public String getMetricKey() {
    return metricKey;
  }

  public String getPeriod() {
    return period;
  }

  public String getGrain() {
    return grain;
  }

  public UUID getSubjectId() {
    return subjectId;
  }

  public long getValue() {
    return value;
  }

  @Override
  public String toString() {
    return "MetricInput[metricKey="
        + metricKey
        + ", period="
        + period
        + ", subjectId="
        + subjectId
        + "]";
  }
}
