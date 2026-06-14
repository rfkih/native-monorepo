package id.co.nativeapp.finance.revenue;

/**
 * Thrown when a consumed {@code SaleRecorded} record carries no valid durable event id — a missing
 * {@code id} Kafka header, or a header whose value is not a UUID.
 *
 * <p>The Debezium outbox event router always stamps the outbox row's {@code id} (a UUID) onto this
 * header, so its absence or malformation means a <strong>misconfigured producer</strong>, not a
 * normal redelivery. The consumer fails closed: it cannot dedupe by a durable id, and synthesising
 * one from the Kafka topic-partition-offset would defeat dedupe across a rebalance / compacted
 * replay / repartition (the same logical event would be processed twice under different ids),
 * double-counting money — exactly what HR-3 forbids.
 *
 * <p>This is a deterministic, non-transient failure, so the container's error handler (see {@link
 * id.co.nativeapp.finance.config.KafkaConfig}) treats it as <em>non-retryable</em> and routes the
 * record straight to {@code SaleRecorded.DLT} — no retry budget is spent on a record that can never
 * succeed, and money is never silently dropped (it is preserved on the DLT for inspection).
 */
public class MissingEventIdException extends RuntimeException {

  public MissingEventIdException(String message) {
    super(message);
  }

  public MissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
