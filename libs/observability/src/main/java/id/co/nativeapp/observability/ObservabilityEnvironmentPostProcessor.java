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
 * <p>Sets {@code management.tracing.sampling.probability=1.0} (Boot default is 0.1, which would
 * leave 90 % of log lines without a {@code traceId} and break outbox→Kafka trace continuity) and
 * disables the OTLP metrics push registry (no collector wired yet).
 *
 * <p><strong>Why {@code management.tracing.export.enabled} is intentionally NOT set here:</strong>
 * Spring Boot 4.1 uses {@code @ConditionalOnEnabledTracingExport} to gate the W3C
 * {@code TextMapPropagator} bean. Setting that flag to {@code false} suppresses the propagator,
 * making every Kafka listener start a new root span instead of continuing the producer's trace.
 * OTLP span export is safely off anyway because no {@code management.opentelemetry.tracing.export.otlp.endpoint}
 * is configured, so the {@code OtlpTracingConnectionDetails} bean never materialises and the OTLP
 * exporter is never created — no collector is contacted. W3C propagation must remain active so
 * the outbox→CDC→Kafka→listener trace hop works end-to-end (ADR 0010 #13).
 *
 * <p>Registered in {@code META-INF/spring.factories}; added with {@code addLast} (lowest
 * precedence) and run last so it only fills a gap the application config left — never an override.
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
            // Disable the OTLP metrics push registry — no collector is wired yet (ADR 0010 follow-
            // up); Prometheus scrape is the active metrics path. The OTLP SPAN exporter is absent
            // without a configured endpoint and does NOT need to be turned off here — see Javadoc.
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
