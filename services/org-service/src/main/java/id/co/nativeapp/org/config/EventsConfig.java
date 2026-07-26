package id.co.nativeapp.org.config;

import id.co.nativeapp.events.MicrometerTraceparentSupplier;
import id.co.nativeapp.events.OutboxLagMetrics;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.TraceparentSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * a {@code @Transactional} method — the {@code CompanyCreated} outbox row commits atomically with
 * the company + first org_unit (rule 3, the transactional outbox).
 *
 * <p>W3C traceparent capture (ADR 0010 #13): see {@link
 * id.co.nativeapp.restaurant.config.EventsConfig} for the pattern. The {@link Tracer} is injected
 * via {@link ObjectProvider} so a missing bean degrades gracefully to a no-op supplier.
 *
 * <p>Outbox-lag metric (ADR 0010 #13): {@link OutboxLagMetrics} registers a Micrometer gauge {@code
 * native.outbox.unpublished} (tag: {@code service}).
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
}
