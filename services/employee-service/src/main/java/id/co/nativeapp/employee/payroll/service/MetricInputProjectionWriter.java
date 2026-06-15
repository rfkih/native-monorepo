package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.MetricInput;
import id.co.nativeapp.employee.payroll.dto.MetricProjectedEvent;
import id.co.nativeapp.employee.payroll.repository.MetricInputRepository;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit that projects a consumed {@code MetricPublished} into
 * {@link MetricInput} — idempotently. A distinct bean so the Spring proxy + auto-RLS aspect engage
 * (rule 5); the caller binds the tenant from the event's {@code company_id} first.
 *
 * <p>Idempotency (rule 3): {@link ProcessedEventStore#processOnce} claims the event UUID and runs
 * the upsert only on the FIRST delivery, in ONE transaction with the write — a re-delivery is a
 * no-op.
 */
@Component
public class MetricInputProjectionWriter {

  private final MetricInputRepository repository;
  private final ProcessedEventStore processedEvents;

  public MetricInputProjectionWriter(
      MetricInputRepository repository, ProcessedEventStore processedEvents) {
    this.repository = repository;
    this.processedEvents = processedEvents;
  }

  /**
   * Applies the metric exactly once per event id (must run inside a {@link TenantContext} scope
   * bound to the event's company_id).
   *
   * @return {@code true} if applied (first delivery), {@code false} if a skipped duplicate.
   */
  @Transactional
  public boolean apply(MetricProjectedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  private void upsert(MetricProjectedEvent event) {
    String tenant = TenantContext.require().companyId();
    Optional<MetricInput> existing =
        repository.findByMetricKeyAndPeriodAndGrainAndSubjectId(
            event.metricKey(), event.period(), event.grain(), event.subjectId());
    if (existing.isPresent()) {
      MetricInput metric = existing.get();
      metric.applyValue(event.value());
      repository.save(metric);
      return;
    }
    MetricInput metric =
        new MetricInput(
            event.metricKey(), event.period(), event.grain(), event.subjectId(), event.value());
    metric.setCompanyId(tenant);
    repository.save(metric);
  }
}
