package id.co.nativeapp.finance.config;

import id.co.nativeapp.events.MicrometerTraceparentSupplier;
import id.co.nativeapp.events.OutboxLagMetrics;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.events.TraceparentSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code libs/events} {@link ProcessedEventStore} and {@link OutboxWriter} into the
 * service context.
 *
 * <p>finance-service was purely downstream until P3d SEAM 4a; it now also PRODUCES (it emits {@code
 * ConsolidationClosed} on a close and {@code TrialBalancePublished} on a within-company close), so
 * it declares an {@link OutboxWriter} alongside the {@link ProcessedEventStore}. {@code
 * libs/events} ships both as plain classes (a {@link JdbcTemplate} wrapper) so they stay
 * framework-light; the service declares the beans here, bound to its own datasource-backed {@code
 * JdbcTemplate}. Because the {@code processOnce} claim AND the outbox INSERT both run on that
 * {@code JdbcTemplate}'s connection — the caller's own transactional connection when invoked inside
 * a {@code @Transactional} method — the dedupe insert, the outbox row, and the close's read-model
 * writes all commit atomically (rule 3 / HR-3): a re-delivered event never double-counts, and an
 * emitted event is never published unless the close that produced it committed.
 *
 * <p>W3C traceparent capture (ADR 0010 #13): the {@link OutboxWriter} is given a {@link
 * TraceparentSupplier} backed by the Micrometer {@link Tracer} so the current span's traceparent
 * is stamped into the outbox row at write time. The {@link Tracer} is injected via {@link
 * ObjectProvider} so a missing bean degrades gracefully to a no-op supplier.
 *
 * <p>Outbox-lag metric (ADR 0010 #13): {@link OutboxLagMetrics} registers a Micrometer gauge
 * {@code native.outbox.unpublished} (tag: {@code service}).
 */
@Configuration
public class EventsConfig {

  @Bean
  public ProcessedEventStore processedEventStore(JdbcTemplate jdbcTemplate) {
    return new ProcessedEventStore(jdbcTemplate);
  }

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
  public OutboxLagMetrics outboxLagMetrics(
      JdbcTemplate jdbcTemplate,
      MeterRegistry meterRegistry,
      @Value("${spring.application.name}") String serviceName) {
    return new OutboxLagMetrics(jdbcTemplate, meterRegistry, serviceName);
  }

  /** A UTC clock for stamping the outbox {@code occurred_at} on a produced event. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
