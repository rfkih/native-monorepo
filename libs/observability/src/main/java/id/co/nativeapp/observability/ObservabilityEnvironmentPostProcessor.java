package id.co.nativeapp.observability;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes fleet-wide OBSERVABILITY DEFAULTS (ADR 0010) as a LOWEST-precedence property source —
 * every service that depends on libs/observability inherits them from one place, yet any service /
 * environment / profile can still override them in {@code application.yml} or via an env var.
 *
 * <p>Today it sets {@code management.tracing.sampling.probability=1.0}. Spring Boot's default is
 * {@code 0.1} (sample 10%), which would leave ~90% of log lines without a {@code traceId} and break
 * cross-hop trace continuity for most requests — Native is a low-volume B2B SaaS where full
 * sampling is affordable and the §5 "every log line correlates to its span" goal needs it.
 * Registered in {@code META-INF/spring.factories}; added with {@code addLast} (lowest precedence)
 * and run last so it only fills a gap the application config left — never an override.
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String PROPERTY_SOURCE_NAME = "nativeObservabilityDefaults";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
      return;
    }
    Map<String, Object> defaults =
        Map.of(
            // Sample every request so every log line carries a traceId (Boot's default is 0.1).
            "management.tracing.sampling.probability", "1.0",
            // Tracing + MDC correlation + W3C propagation stay ON, but DO NOT ship spans or metrics
            // to
            // an OTLP collector by default: there is none wired yet (an infra-gated follow-up, ADR
            // 0010), and the OTLP exporters' default endpoint (localhost:4318) would otherwise log
            // a
            // connection failure on every flush. An environment with a collector flips these on and
            // sets the endpoint; metrics are still scraped via the existing Prometheus registry.
            "management.tracing.export.enabled", "false",
            "management.otlp.metrics.export.enabled", "false");
    environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
  }

  @Override
  public int getOrder() {
    // Run after Boot's config-data processing (which is high precedence) so application.yml sources
    // already exist when this adds its source LAST — making these values defaults, not overrides.
    return Ordered.LOWEST_PRECEDENCE;
  }
}
