package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Tracing wiring (ADR 0010, ENGINEERING-STANDARDS §5 #10): the Micrometer Tracing OpenTelemetry
 * bridge from libs/observability gives every service a {@link Tracer}, full sampling (the {@code
 * ObservabilityEnvironmentPostProcessor} default), and a valid W3C trace id — the basis for the
 * {@code traceId}/{@code spanId} that populate the shared JSON logs.
 */
@SpringBootTest
class TracingWiringTest extends PostgresRlsTestBase {

  @Autowired private Tracer tracer;
  @Autowired private Environment environment;

  @Test
  void aTracerIsWiredFleetWide() {
    assertThat(tracer).isNotNull();
  }

  @Test
  void fullSamplingIsTheFleetDefault() {
    // Set by ObservabilityEnvironmentPostProcessor (libs/observability) — Spring Boot's own default
    // is 0.1, which would leave most log lines without a traceId.
    assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
  }

  @Test
  void aSpanInScopeCarriesAValidW3cTraceId() {
    // Read the id INSIDE the span's scope (the OTel bridge derives context() from the current
    // span). A valid W3C trace-id is 32 lowercase hex chars and never the all-zero id — the
    // all-zero case would also prove a no-op SDK (no real TracerProvider wired), so this asserts
    // the OTel SDK is genuinely active, not just that a Tracer bean exists.
    Span span = tracer.nextSpan().name("wiring-check").start();
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      String traceId = tracer.currentSpan().context().traceId();
      assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
    } finally {
      span.end();
    }
  }
}
