package id.co.nativeapp.carwash.config;

import id.co.nativeapp.carwash.entitlement.service.ProjectionEntitlementLoader;
import id.co.nativeapp.carwash.ticket.service.TicketPostOutboxHook;
import id.co.nativeapp.carwash.wash.service.PostOutboxHook;
import id.co.nativeapp.entitlementcheck.CachedEntitlementChecker;
import id.co.nativeapp.entitlementcheck.EntitlementCache;
import id.co.nativeapp.events.MicrometerTraceparentSupplier;
import id.co.nativeapp.events.OutboxLagMetrics;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.events.TraceparentSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Service-specific wiring for the {@code libs/events} outbox + dedupe primitives and the {@code
 * libs/entitlement-check} cache/checker. This is the ONLY event/cache config the service declares —
 * NO RLS/persistence/advice copies; those are inherited from {@code libs/tenant} + {@code
 * libs/security} auto-configurations.
 *
 * <ul>
 *   <li>{@link OutboxWriter} — the transactional outbox writer (rule 3): its single {@code INSERT}
 *       runs on the caller's transactional {@link JdbcTemplate} connection, so a {@code
 *       SaleRecorded}/{@code MetricPublished} row commits atomically with the wash. Stamped with the
 *       W3C traceparent from the active span (ADR 0010 #13).
 *   <li>{@link ProcessedEventStore} — the idempotent-consumer dedupe store: its {@code processOnce}
 *       claim runs in the same transaction as the projection upsert, so a re-delivered entitlement
 *       / staff event never double-applies.
 *   <li>{@link EntitlementCache} + {@link CachedEntitlementChecker} — the shared Redis-cached
 *       entitled? gate.
 *   <li>{@link OutboxLagMetrics} — Micrometer gauge {@code native.outbox.unpublished} (ADR 0010 #13).
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CarwashProperties.class)
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

  @Bean
  public EntitlementCache entitlementCache(
      StringRedisTemplate redisTemplate, CarwashProperties properties) {
    return new EntitlementCache(redisTemplate, properties.cacheTtl());
  }

  @Bean
  public CachedEntitlementChecker entitlementChecker(
      EntitlementCache entitlementCache, ProjectionEntitlementLoader loader) {
    return new CachedEntitlementChecker(entitlementCache, loader);
  }

  /**
   * The production no-op post-outbox hook ({@link id.co.nativeapp.carwash.wash.service.WashWriter
   * WashWriter} test seam). Declared {@link ConditionalOnMissingBean} so the atomicity test can
   * supply a throwing hook in its own context without a bean-definition clash.
   */
  @Bean
  @ConditionalOnMissingBean
  public PostOutboxHook postOutboxHook() {
    return new PostOutboxHook.Noop();
  }

  /**
   * The production no-op post-outbox hook for the ticket feature ({@link
   * id.co.nativeapp.carwash.ticket.service.TicketWriter TicketWriter} test seam). Declared {@link
   * ConditionalOnMissingBean} so the ticket atomicity test can supply a throwing hook in its own
   * context without a bean-definition clash, independent of the wash feature's hook.
   */
  @Bean
  @ConditionalOnMissingBean
  public TicketPostOutboxHook ticketPostOutboxHook() {
    return new TicketPostOutboxHook.Noop();
  }
}
