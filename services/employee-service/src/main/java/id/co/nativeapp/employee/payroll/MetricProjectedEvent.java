package id.co.nativeapp.employee.payroll;

import java.util.UUID;

/**
 * A decoded {@code MetricPublished} reduced to what the {@link MetricInput} projection needs.
 *
 * @param eventId the event UUID (idempotency key)
 * @param companyId the tenant the consumer binds the write to (from the event)
 * @param metricKey the metric key
 * @param period the period
 * @param grain the grain (employee | shift | outlet)
 * @param subjectId the grain subject id
 * @param value the metric value (a long; never a float)
 */
public record MetricProjectedEvent(
    UUID eventId,
    String companyId,
    String metricKey,
    String period,
    String grain,
    UUID subjectId,
    long value) {}
