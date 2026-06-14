package id.co.nativeapp.finance.config;

import id.co.nativeapp.events.ProcessedEventStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code libs/events} {@link ProcessedEventStore} into the service context.
 *
 * <p>finance-service is purely downstream: it does not publish, so it declares NO {@link
 * id.co.nativeapp.events.OutboxWriter OutboxWriter}. What it needs from {@code libs/events} is the
 * idempotent-consumer dedupe store. {@code libs/events} ships {@code ProcessedEventStore} as a
 * plain class (a {@link JdbcTemplate} wrapper) so it stays framework-light; the service declares
 * the bean here, bound to its own datasource-backed {@code JdbcTemplate}. Because the {@code
 * processOnce} claim runs on that {@code JdbcTemplate}'s connection — the caller's own
 * transactional connection when invoked inside a {@code @Transactional} method — the dedupe insert
 * commits atomically with the ledger posting and the read-model update (rule 3 / HR-3): a
 * re-delivered {@code SaleRecorded} never double-counts.
 */
@Configuration
public class EventsConfig {

  @Bean
  public ProcessedEventStore processedEventStore(JdbcTemplate jdbcTemplate) {
    return new ProcessedEventStore(jdbcTemplate);
  }
}
