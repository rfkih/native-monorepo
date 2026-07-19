package id.co.nativeapp.employee.config;

import id.co.nativeapp.events.MicrometerTraceparentSupplier;
import id.co.nativeapp.events.OutboxLagMetrics;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.events.TraceparentSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code libs/events} {@link OutboxWriter} and {@link ProcessedEventStore} into the
 * service context.
 *
 * <p>employee-service is BOTH a producer and a consumer, so it needs both:
 *
 * <ul>
 *   <li>{@link OutboxWriter} — the ONLY sanctioned way to publish (rule 3). {@code EmployeeChanged}
 *       / {@code AssignmentChanged} are written through it inside the same {@code @Transactional}
 *       unit that mutates the aggregate, so the outbox row commits atomically with the change.
 *       Stamped with the W3C traceparent from the active span (ADR 0010 #13).
 *   <li>{@link ProcessedEventStore} — the idempotent-consumer dedupe store for the {@code
 *       OrgUnitCreated}/{@code OrgUnitChanged} listener: its {@code processOnce} claim runs on the
 *       caller's transactional connection, so the dedupe insert and the read-model upsert commit
 *       together — a re-delivered org event never double-applies.
 *   <li>{@link OutboxLagMetrics} — Micrometer gauge {@code native.outbox.unpublished} (ADR 0010 #13).
 * </ul>
 *
 * <p>Both are plain {@link JdbcTemplate} wrappers (framework-light by design), declared here bound
 * to the service's own datasource-backed {@code JdbcTemplate}.
 */
@Configuration
public class EventsConfig {

  @Bean
  public TraceparentSupplier traceparentSupplier(ObjectProvider<Tracer> tracerProvider) {
    Tracer tracer = tracerProvider.getIfAvailable();
    if (tracer == null) {
      return TraceparentSupplier.NOOP;
    }
    return new MicrometerTraceparentSupplier(tracer);
  }

  @Bean
  public OutboxWriter outboxWriter(
      JdbcTemplate jdbcTemplate, TraceparentSupplier traceparentSupplier) {
    return new OutboxWriter(jdbcTemplate, traceparentSupplier);
  }

  @Bean
  public ProcessedEventStore processedEventStore(JdbcTemplate jdbcTemplate) {
    return new ProcessedEventStore(jdbcTemplate);
  }

  @Bean
  public OutboxLagMetrics outboxLagMetrics(
      JdbcTemplate jdbcTemplate,
      MeterRegistry meterRegistry,
      @Value("${spring.application.name}") String serviceName) {
    return new OutboxLagMetrics(jdbcTemplate, meterRegistry, serviceName);
  }
}
