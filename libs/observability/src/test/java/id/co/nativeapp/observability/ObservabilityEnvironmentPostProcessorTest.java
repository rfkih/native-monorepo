package id.co.nativeapp.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit test for the fleet-wide observability defaults (ADR 0010): the post-processor must fill the
 * gaps the application config left — full sampling so every log line carries a {@code traceId}, the
 * OTLP metrics push off — as LOWEST-precedence values a service can always override, and it must be
 * idempotent when the source is already registered (a context refresh must not duplicate it).
 */
class ObservabilityEnvironmentPostProcessorTest {

  private final ObservabilityEnvironmentPostProcessor postProcessor =
      new ObservabilityEnvironmentPostProcessor();

  @Test
  void contributesTheAdr0010DefaultsAsALowestPrecedenceSource() {
    MockEnvironment environment = new MockEnvironment();

    postProcessor.postProcessEnvironment(environment, null);

    assertThat(environment.getPropertySources().contains("nativeObservabilityDefaults")).isTrue();
    assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
    assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
        .isEqualTo("false");
    // addLast + run-last make these DEFAULTS: an existing application value must win.
    assertThat(environment.getPropertySources())
        .last()
        .extracting(org.springframework.core.env.PropertySource::getName)
        .isEqualTo("nativeObservabilityDefaults");
    assertThat(postProcessor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }

  @Test
  void isIdempotentWhenTheDefaultsSourceIsAlreadyRegistered() {
    MockEnvironment environment = new MockEnvironment();
    MapPropertySource preExisting =
        new MapPropertySource(
            "nativeObservabilityDefaults",
            Map.of("management.tracing.sampling.probability", "0.5"));
    environment.getPropertySources().addLast(preExisting);

    postProcessor.postProcessEnvironment(environment, null);

    // The early-return branch: the pre-existing source must survive untouched, not be replaced.
    assertThat(environment.getPropertySources().get("nativeObservabilityDefaults"))
        .isSameAs(preExisting);
    assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.5");
  }
}
