package id.co.nativeapp.employee.config;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code libs/events} {@link OutboxWriter} and {@link ProcessedEventStore} into the
 * service context.
 *
 * <p>employee-service is BOTH a producer and a consumer, so it needs both:
 *
 * <ul>
 *   <li>{@link OutboxWriter} — the ONLY sanctioned way to publish (rule 3). {@code EmployeeChanged}
 *       / {@code AssignmentChanged} are written through it inside the same {@code @Transactional}
 *       unit that mutates the aggregate, so the outbox row commits atomically with the change.
 *   <li>{@link ProcessedEventStore} — the idempotent-consumer dedupe store for the {@code
 *       OrgUnitCreated}/{@code OrgUnitChanged} listener: its {@code processOnce} claim runs on the
 *       caller's transactional connection, so the dedupe insert and the read-model upsert commit
 *       together — a re-delivered org event never double-applies.
 * </ul>
 *
 * <p>Both are plain {@link JdbcTemplate} wrappers (framework-light by design), declared here bound
 * to the service's own datasource-backed {@code JdbcTemplate}.
 */
@Configuration
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
