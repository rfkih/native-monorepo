package id.co.nativeapp.events;

/**
 * A functional interface that returns the W3C {@code traceparent} string for the CURRENT span, or
 * {@code null} when no span is in scope.
 *
 * <p>The production implementation ({@link MicrometerTraceparentSupplier}) reads from the
 * Micrometer {@code Tracer}. The interface is intentionally kept in {@code libs/events} so the
 * outbox writer can capture a traceparent without a hard runtime dependency on Micrometer — callers
 * that have no {@code Tracer} (tests, DB-only modules) pass a {@code () -> null} lambda.
 *
 * <p>A {@code null} return is normal: it means the call happened outside any traced context and no
 * {@code traceparent} column will be set on the outbox row.
 */
@FunctionalInterface
public interface TraceparentSupplier {

  /**
   * Returns the W3C {@code traceparent} for the active span in the form {@code
   * 00-<32-hex-traceId>-<16-hex-spanId>-<01|00>}, or {@code null} when there is no active span.
   */
  String get();

  /** A no-op supplier that always returns {@code null}. Useful in tests and DB-only contexts. */
  TraceparentSupplier NOOP = () -> null;
}
