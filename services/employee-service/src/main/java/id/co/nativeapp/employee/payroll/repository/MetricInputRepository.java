package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.MetricInput;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for the {@link MetricInput} projection. Derived queries only; RLS-scoped (rule
 * 5). {@code PER_METRIC_UNIT} earning rules read this (never a sync call — rule 2).
 */
public interface MetricInputRepository extends JpaRepository<MetricInput, UUID> {

  /** The upsert lookup on the natural key (idempotent re-projection). */
  Optional<MetricInput> findByMetricKeyAndPeriodAndGrainAndSubjectId(
      String metricKey, String period, String grain, UUID subjectId);

  /**
   * All metric inputs for a metric key + subject whose stored {@code period} BEGINS WITH the run
   * month prefix ({@code "YYYY-MM"}) — so a daily-grain row ({@code "YYYY-MM-DD"}) AND a monthly
   * row ({@code "YYYY-MM"}) both match a {@code PER_METRIC_UNIT} earning resolved by run month. The
   * caller sums their values. A derived prefix query (no load-all-and-filter in Java); RLS-scoped
   * (rule 5).
   */
  List<MetricInput> findByMetricKeyAndSubjectIdAndPeriodStartingWith(
      String metricKey, UUID subjectId, String periodPrefix);
}
