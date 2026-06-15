package id.co.nativeapp.entitlement.config;

import id.co.nativeapp.entitlement.entitlement.service.DbEntitlementLoader;
import id.co.nativeapp.entitlement.entitlement.service.PostOutboxHook;
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
 *       runs on the caller's transactional {@link JdbcTemplate} connection, so an {@code
 *       EntitlementGranted}/{@code EntitlementRevoked} row commits atomically with the entitlement
 *       change.
 *   <li>{@link ProcessedEventStore} — the idempotent-consumer dedupe store: its {@code processOnce}
 *       claim runs in the same transaction as the grants, so a re-delivered {@code CompanyCreated}
 *       never double-grants.
 *   <li>{@link EntitlementCache} + {@link CachedEntitlementChecker} — the shared Redis-cached
 *       entitled? gate. entitlement-service supplies the DB-backed {@link DbEntitlementLoader}
 *       (queries its own {@code tenant_entitlement}) and seeds/invalidates the cache from its own
 *       events. The checker is a single bean the service both queries and invalidates through.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(EntitlementProperties.class)
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
      StringRedisTemplate redisTemplate, EntitlementProperties properties) {
    return new EntitlementCache(redisTemplate, properties.cacheTtl());
  }

  @Bean
  public CachedEntitlementChecker entitlementChecker(
      EntitlementCache entitlementCache, DbEntitlementLoader loader) {
    return new CachedEntitlementChecker(entitlementCache, loader);
  }

  /**
   * The production no-op post-outbox hook ({@link
   * id.co.nativeapp.entitlement.entitlement.service.EntitlementWriter EntitlementWriter} test
   * seam). Declared {@link ConditionalOnMissingBean} so the atomicity test can supply a throwing
   * hook in its own context without a bean-definition clash.
   */
  @Bean
  @ConditionalOnMissingBean
  public PostOutboxHook postOutboxHook() {
    return new PostOutboxHook.Noop();
  }
}
