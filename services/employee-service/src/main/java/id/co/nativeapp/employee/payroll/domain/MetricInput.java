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
 * inputs. Keyed by (company_id, metric_key, period, grain, subject_id) -> value. {@code
 * PER_METRIC_UNIT} earning rules read this projection (never a sync call — rule 2). An
 * app-maintained projection, so (like the org read model) it extends {@link Auditable} (rule 4) and
 * is RLS-scoped (rule 5); the consumer writes it bound to the EVENT's company_id. No PII (a
 * count/amount of operational activity, not individual salary).
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

  /** Refreshes the value from a re-projected metric (an upsert on the natural key). */
  public void applyValue(long newValue) {
    this.value = newValue;
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
