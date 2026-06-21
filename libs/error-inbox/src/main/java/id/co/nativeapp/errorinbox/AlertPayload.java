package id.co.nativeapp.errorinbox;

import java.time.Instant;

/**
 * Payload POSTed to the configured webhook URL when an error reaches an alert milestone (first
 * occurrence or a round-number repeat). Jackson-serializable via its record accessors.
 *
 * <p>All fields are informational diagnostics; none carries PII ({@code message} is already the
 * {@code redacted_message} from {@code error_log}).
 */
public record AlertPayload(
    String service,
    String fingerprint,
    String exceptionClass,
    String message,
    String source,
    long occurrenceCount,
    Instant lastSeen) {}
