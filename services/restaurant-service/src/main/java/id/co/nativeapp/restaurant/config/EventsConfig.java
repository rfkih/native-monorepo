package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.events.MicrometerTraceparentSupplier;
import id.co.nativeapp.events.OutboxLagMetrics;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.events.TraceparentSupplier;
import id.co.nativeapp.restaurant.sale.service.PostOutboxHook;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code libs/events} {@link OutboxWriter} into the service context.
 *
 * <p>{@code libs/events} ships {@code OutboxWriter} as a plain class (a {@link JdbcTemplate}
 * wrapper) so it stays framework-light; the service declares the bean here, bound to its own
 * datasource-backed {@code JdbcTemplate}. Because the writer's single {@code INSERT} runs on that
 * {@code JdbcTemplate}'s connection — the caller's own transactional connection when invoked inside
 * a {@code @Transactional} method — the outbox row commits atomically with the sale (rule 3, the
 * transactional outbox).
 *
 * <p>W3C traceparent capture (ADR 0010 #13): the {@link OutboxWriter} is given a {@link
 * TraceparentSupplier} backed by the Micrometer {@link Tracer} so the current span's traceparent is
 * stamped into the outbox row at write time. Debezium maps the {@code traceparent} column to a
 * Kafka header, and consumers restore the trace context so their listener span is a child of the
 * producer span — closing the outbox→Kafka trace gap. The {@link Tracer} is injected via {@link
 * ObjectProvider} so a missing bean degrades gracefully to a no-op supplier rather than failing the
 * context.
 *
 * <p>Outbox-lag metric (ADR 0010 #13): {@link OutboxLagMetrics} registers a Micrometer gauge {@code
 * native.outbox.unpublished} (tag: {@code service}) that counts unpublished outbox rows so Grafana
 * can alert on Debezium relay lag.
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
  public OutboxLagMetrics outboxLagMetrics(
      JdbcTemplate jdbcTemplate,
      MeterRegistry meterRegistry,
      @Value("${spring.application.name}") String serviceName) {
    return new OutboxLagMetrics(jdbcTemplate, meterRegistry, serviceName);
  }

  /**
   * The idempotent-consumer dedupe store used by {@link
   * id.co.nativeapp.restaurant.outletref.service.UserOutletAssignmentRefWriter}. Keyed by the
   * globally-unique event UUID; no tenant scope needed (the event id is unique across all tenants).
   * Requires the {@code processed_event} table (V15 migration).
   */
  @Bean
  public ProcessedEventStore processedEventStore(JdbcTemplate jdbcTemplate) {
    return new ProcessedEventStore(jdbcTemplate);
  }

  /**
   * The production no-op post-outbox hook ({@link
   * id.co.nativeapp.restaurant.sale.service.SaleWriter SaleWriter} test seam). Declared {@link
   * ConditionalOnMissingBean} so the atomicity test can supply a throwing hook in its own context
   * without a bean-definition clash.
   */
  @Bean
  @ConditionalOnMissingBean
  public PostOutboxHook postOutboxHook() {
    return new PostOutboxHook.Noop();
  }
}
