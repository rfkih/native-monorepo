package id.co.nativeapp.finance.grouptb;

/**
 * Thrown when a {@code TrialBalancePublished} payload cannot be decoded from its raw Avro bytes
 * (garbage / truncated / wrong schema). NON-RETRYABLE: the consumer routes it straight to {@code
 * TrialBalancePublished.DLT} (registered in {@code KafkaConfig}) — a deterministic poison record
 * never wastes the retry budget and the trial balance is preserved for inspection, never silently
 * dropped.
 */
public class TrialBalancePublishedDecodeException extends RuntimeException {

  public TrialBalancePublishedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
