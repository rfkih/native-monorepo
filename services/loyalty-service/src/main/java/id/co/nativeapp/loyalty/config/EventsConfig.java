package id.co.nativeapp.loyalty.config;

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
 * Service-specific wiring for the {@code libs/events} outbox + dedupe primitives. This is the ONLY
 * event config the service declares — NO RLS/persistence/advice copies; those are inherited from
 * {@code libs/tenant} + {@code libs/security} auto-configurations. Unlike the vertical services
 * this is ported from, there is no {@code libs/entitlement-check} wiring here (loyalty is not
 * module-gated this phase).
 *
 * <ul>
 *   <li>{@link OutboxWriter} — the transactional outbox writer (rule 3): its single {@code INSERT}
 *       runs on the caller's transactional {@link JdbcTemplate} connection, so a {@code
 *       LoyaltyBalanceChanged}/{@code GiftCardStateChanged}/{@code LoyaltyRedemptionFlagged} row
 *       commits atomically with the ledger mutation. Stamped with the W3C traceparent from the
 *       active span (ADR 0010 #13).
 *   <li>{@link ProcessedEventStore} — the idempotent-consumer dedupe store: its {@code processOnce}
 *       claim runs in the same transaction as the ledger upsert, so a re-delivered {@code
 *       SaleRecorded}/{@code GiftCardSold}/{@code SaleVoided}/{@code SaleRefunded} never
 *       double-applies.
 *   <li>{@link OutboxLagMetrics} — Micrometer gauge {@code native.outbox.unpublished} (ADR 0010
 *       #13).
 * </ul>
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
