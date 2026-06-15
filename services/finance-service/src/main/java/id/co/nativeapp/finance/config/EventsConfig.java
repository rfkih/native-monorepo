package id.co.nativeapp.finance.config;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import java.time.Clock;
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
 */
@Configuration
public class EventsConfig {

  @Bean
  public ProcessedEventStore processedEventStore(JdbcTemplate jdbcTemplate) {
    return new ProcessedEventStore(jdbcTemplate);
  }

  @Bean
  public OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate) {
    return new OutboxWriter(jdbcTemplate);
  }

  /** A UTC clock for stamping the outbox {@code occurred_at} on a produced event. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
