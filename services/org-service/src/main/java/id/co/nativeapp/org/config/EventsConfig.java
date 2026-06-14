package id.co.nativeapp.org.config;

import id.co.nativeapp.events.OutboxWriter;
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
 */
@Configuration
public class EventsConfig {

  @Bean
  public OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate) {
    return new OutboxWriter(jdbcTemplate);
  }
}
