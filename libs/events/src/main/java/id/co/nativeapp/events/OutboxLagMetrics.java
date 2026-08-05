package id.co.nativeapp.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers a Micrometer gauge {@code native.outbox.unpublished} that tracks the count of outbox
 * rows with {@code published_at IS NULL} — rows that have been written but not yet tailed by
 * Debezium (i.e. the outbox relay lag).
 *
 * <p>The gauge is tagged with {@code service} (the value of {@code spring.application.name}) so
 * Grafana dashboards can distinguish per-producer lag. The query is {@code COUNT(*) WHERE
 * published_at IS NULL}, which is cheap because the baseline migration creates a partial index on
 * exactly that predicate ({@code idx_outbox_unpublished}).
 *
 * <p>Register this component in each producer service's {@code EventsConfig}:
 *
 * <pre>{@code
 * @Bean
 * public OutboxLagMetrics outboxLagMetrics(
 *     JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
 *     @Value("${spring.application.name}") String serviceName) {
 *   return new OutboxLagMetrics(jdbcTemplate, meterRegistry, serviceName);
 * }
 * }</pre>
 *
 * <p>The metric name {@code native.outbox.unpublished} is the exact name Grafana dashboards will
 * use — do NOT rename it without also updating the dashboard definitions.
 */
public class OutboxLagMetrics {

  static final String METRIC_NAME = "native.outbox.unpublished";
  private static final String QUERY = "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL";

  private final JdbcTemplate jdbcTemplate;

  /**
   * Creates and immediately registers the gauge against the given {@link MeterRegistry}.
   *
   * @param jdbcTemplate the JDBC template wired to the service's own datasource
   * @param meterRegistry the Micrometer registry (provided by Spring Boot actuator
   *     autoconfiguration)
   * @param serviceName the value of {@code spring.application.name}; used as the {@code service}
   *     tag
   */
  public OutboxLagMetrics(
      JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, String serviceName) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    Objects.requireNonNull(meterRegistry, "meterRegistry");
    Objects.requireNonNull(serviceName, "serviceName");
    Gauge.builder(METRIC_NAME, this, OutboxLagMetrics::countUnpublished)
        .description("Count of outbox rows not yet tailed by Debezium (published_at IS NULL)")
        .tag("service", serviceName)
        .register(meterRegistry);
  }

  private double countUnpublished() {
    Long count = jdbcTemplate.queryForObject(QUERY, Long.class);
    return count == null ? 0.0 : (double) count;
  }
}
