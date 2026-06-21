package id.co.nativeapp.errorinbox;

import java.time.Clock;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;

/**
 * Facade called by the Kafka DLT path to record a consume failure in the error inbox and optionally
 * fire a webhook alert (ADR 0005). Service-agnostic — the {@code service} label in the alert comes
 * from {@code spring.application.name} (injected by {@link ErrorInboxAutoConfiguration}).
 *
 * <p>{@link #record(ConsumerRecord, Exception)} is the single entry point. It:
 *
 * <ol>
 *   <li>Derives {@code source} from the consumer record's topic.
 *   <li>Pulls the W3C {@code trace_id} from MDC (nullable — not available on a Kafka thread).
 *   <li>Delegates to {@link ErrorInboxWriter} to upsert the error and get the post-upsert {@code
 *       occurrence_count}.
 *   <li>If {@link #shouldAlert(long)} is true, builds an {@link AlertPayload} and fires it via
 *       {@link AlertWebhookClient}.
 * </ol>
 *
 * <p>This method MUST NOT throw under any circumstance — ops infrastructure must never break the
 * DLT publishing path that follows.
 */
public class ConsumeErrorRecorder {

  private static final Logger log = LoggerFactory.getLogger(ConsumeErrorRecorder.class);

  private final ErrorInboxWriter errorInboxWriter;
  private final AlertWebhookClient alertWebhookClient;
  private final Clock clock;
  private final String serviceName;

  public ConsumeErrorRecorder(
      ErrorInboxWriter errorInboxWriter,
      AlertWebhookClient alertWebhookClient,
      Clock clock,
      String serviceName) {
    this.errorInboxWriter = errorInboxWriter;
    this.alertWebhookClient = alertWebhookClient;
    this.clock = clock;
    this.serviceName = serviceName;
  }

  /**
   * Records the consume failure and fires an alert if the occurrence count hits a milestone.
   *
   * <p>{@code companyId} is left {@code null} — at the DLT layer the Avro bytes may not be
   * parseable, so tenant resolution is not possible. It is stored as nullable diagnostic context
   * only (ADR 0005).
   *
   * @param rec the Kafka record that failed; must not be {@code null}
   * @param ex the exception surfaced by the listener; must not be {@code null}
   */
  public void record(ConsumerRecord<?, ?> rec, Exception ex) {
    try {
      String source = "kafka:" + rec.topic();
      String traceId = MDC.get("trace_id");

      ErrorInboxWriter.Recorded recorded = errorInboxWriter.record(ex, source, null, traceId);

      if (shouldAlert(recorded.occurrenceCount())) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        AlertPayload payload =
            new AlertPayload(
                serviceName,
                // The REAL dedup fingerprint (also the error_log row key), so an operator can
                // correlate the alert back to the stored row.
                recorded.fingerprint(),
                cause.getClass().getName(),
                // The PII-REDACTED message from the writer — the raw cause.getMessage() must
                // NEVER leave this service over the webhook (HR-6 / ADR 0005).
                recorded.redactedMessage(),
                source,
                recorded.occurrenceCount(),
                Instant.now(clock));
        alertWebhookClient.send(payload);
      }
    } catch (Exception e) {
      log.warn("consume-error-recorder: unexpected failure recording error; continuing", e);
    }
  }

  /**
   * Returns {@code true} when the occurrence count has reached an alert milestone.
   *
   * <p>Milestones: first occurrence ({@code 1}), then {@code 10}, {@code 100}, and every {@code
   * 1000} thereafter ({@code 1000}, {@code 2000}, …).
   *
   * @param n the post-upsert occurrence count
   * @return whether an alert should be sent
   */
  static boolean shouldAlert(long n) {
    return n == 1 || n == 10 || n == 100 || (n >= 1000 && n % 1000 == 0);
  }
}
