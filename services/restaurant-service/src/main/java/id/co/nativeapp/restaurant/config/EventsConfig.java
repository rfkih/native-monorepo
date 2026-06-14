package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.sale.PostOutboxHook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
 * a {@code @Transactional} method — the outbox row commits atomically with the sale (rule 3, the
 * transactional outbox).
 */
@Configuration
public class EventsConfig {

  @Bean
  public OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate) {
    return new OutboxWriter(jdbcTemplate);
  }

  /**
   * The production no-op post-outbox hook ({@link id.co.nativeapp.restaurant.sale.SaleWriter
   * SaleWriter} test seam). Declared {@link ConditionalOnMissingBean} so the atomicity test can
   * supply a throwing hook in its own context without a bean-definition clash.
   */
  @Bean
  @ConditionalOnMissingBean
  public PostOutboxHook postOutboxHook() {
    return new PostOutboxHook.Noop();
  }
}
