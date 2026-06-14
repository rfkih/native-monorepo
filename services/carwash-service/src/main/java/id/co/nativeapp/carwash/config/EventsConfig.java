package id.co.nativeapp.carwash.config;

import id.co.nativeapp.carwash.entitlement.ProjectionEntitlementLoader;
import id.co.nativeapp.carwash.wash.PostOutboxHook;
import id.co.nativeapp.entitlementcheck.CachedEntitlementChecker;
import id.co.nativeapp.entitlementcheck.EntitlementCache;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
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
 *       SaleRecorded}/{@code MetricPublished} row commits atomically with the wash.
 *   <li>{@link ProcessedEventStore} — the idempotent-consumer dedupe store: its {@code processOnce}
 *       claim runs in the same transaction as the projection upsert, so a re-delivered entitlement
 *       / staff event never double-applies.
 *   <li>{@link EntitlementCache} + {@link CachedEntitlementChecker} — the shared Redis-cached
 *       entitled? gate. carwash-service supplies the projection-backed {@link
 *       ProjectionEntitlementLoader} (queries its own {@code entitlement_projection}) and
 *       seeds/invalidates the cache from the {@code EntitlementGranted}/{@code EntitlementRevoked}
 *       events. The checker is a single bean the service both queries (record-wash gate) and
 *       invalidates through (the consumer).
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CarwashProperties.class)
public class EventsConfig {

  @Bean
  public OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate) {
    return new OutboxWriter(jdbcTemplate);
  }

  @Bean
  public ProcessedEventStore processedEventStore(JdbcTemplate jdbcTemplate) {
    return new ProcessedEventStore(jdbcTemplate);
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
   * The production no-op post-outbox hook ({@link id.co.nativeapp.carwash.wash.WashWriter
   * WashWriter} test seam). Declared {@link ConditionalOnMissingBean} so the atomicity test can
   * supply a throwing hook in its own context without a bean-definition clash.
   */
  @Bean
  @ConditionalOnMissingBean
  public PostOutboxHook postOutboxHook() {
    return new PostOutboxHook.Noop();
  }
}
