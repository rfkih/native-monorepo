package id.co.nativeapp.notification.config;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Service-specific wiring for the {@code libs/events} outbox + dedupe primitives. This is the ONLY
 * event config the service declares — NO RLS/persistence/advice copies; those are inherited from
 * {@code libs/tenant} + {@code libs/security} auto-configurations.
 *
 * <ul>
 *   <li>{@link OutboxWriter} — the transactional outbox writer (rule 3): its single {@code INSERT}
 *       runs on the caller's transactional {@link JdbcTemplate} connection, so the {@code
 *       DeliveryReceipt} outbox row commits atomically with the notification + delivery_receipt
 *       rows. notification-service IS a producer (unlike finance), so it declares an OutboxWriter.
 *   <li>{@link ProcessedEventStore} — the idempotent-consumer dedupe store: its {@code processOnce}
 *       claim runs in the same transaction as the create + deliver + receipt + outbox writes, so a
 *       re-delivered {@code ConsolidationClosed} never creates a duplicate notification/receipt.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class EventsConfig {

  @Bean
  public OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate) {
    return new OutboxWriter(jdbcTemplate);
  }

  @Bean
  public ProcessedEventStore processedEventStore(JdbcTemplate jdbcTemplate) {
    return new ProcessedEventStore(jdbcTemplate);
  }
}
