package id.co.nativeapp.events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Objects;

/**
 * A {@link TraceparentSupplier} backed by the Micrometer {@link Tracer}.
 *
 * <p>Formats the current span as a W3C {@code traceparent} header value: {@code
 * 00-<32-hex-traceId>-<16-hex-spanId>-<01|00>} (sampled flag from the context). Returns {@code
 * null} when there is no current span or the context has no trace id (e.g. a no-op tracer).
 *
 * <p>This class lives in {@code libs/events} but declares {@code io.micrometer.tracing.Tracer} only
 * as a {@code compileOnly} dependency — so the class compiles but is never forced onto runtime
 * classpaths that don't already carry Micrometer Tracing (e.g. DB-only test modules). The gateway
 * depends on {@code libs/observability} (which declares the tracing starter as {@code api}) but
 * does NOT depend on {@code libs/events}, so this file never leaks tracing/JDBC concerns into the
 * gateway's dependency graph.
 */
public class MicrometerTraceparentSupplier implements TraceparentSupplier {

  private final Tracer tracer;

  public MicrometerTraceparentSupplier(Tracer tracer) {
    this.tracer = Objects.requireNonNull(tracer, "tracer");
  }

  /**
   * Returns the W3C {@code traceparent} for the current span, or {@code null} when no span is
   * active or the tracer is a no-op (trace id is empty / null).
   */
  @Override
  public String get() {
    Span current = tracer.currentSpan();
    if (current == null) {
      return null;
    }
    TraceContext ctx = current.context();
    String traceId = ctx.traceId();
    String spanId = ctx.spanId();
    if (traceId == null || traceId.isBlank() || spanId == null || spanId.isBlank()) {
      return null;
    }
    // W3C traceparent: version-flags-traceId-parentId-traceFlags
    // sampled flag: "01" if sampled, "00" if not. Micrometer uses Boolean sampled().
    Boolean sampled = ctx.sampled();
    String flags = Boolean.TRUE.equals(sampled) ? "01" : "00";
    return "00-" + traceId + "-" + spanId + "-" + flags;
  }
}
